package com.agronomy.phytomonitor.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agronomy.phytomonitor.actions.SyncConfirmation
import com.agronomy.phytomonitor.actions.SyncTarget
import com.agronomy.phytomonitor.journal.JournalDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.agronomy.phytomonitor.updates.KnowledgeUpdates
import com.agronomy.phytomonitor.updates.UpdateRelease
import com.agronomy.phytomonitor.updates.ModelUpdates

data class SyncNotice(val text: String, val error: Boolean = false)
data class SyncUiState(
    val generation: String? = null,
    val username: String = "",
    val origin: String = "",
    val busy: Boolean = false,
    val authenticationRequired: Boolean = false,
    val records: List<SyncRecord> = emptyList(),
    val pending: SyncConfirmation? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** The last feed read from the server and the reference book that is active on this phone. */
    val updates: List<UpdateRelease> = emptyList(),
    val activeKnowledge: UpdateRelease? = null,
    val offeredModel: UpdateRelease? = null,
    val activeModel: UpdateRelease? = null,
    val pendingModelRestart: Boolean = false,
    val modelNotice: String? = null,
    /** Incremented after a new reference book is activated, so the open catalog is reloaded. */
    val knowledgeRevision: Int = 0
) {
    val offeredKnowledge: UpdateRelease? get() = KnowledgeUpdates.offered(updates, activeKnowledge)

    fun forSession(session: SyncSession?): SyncUiState = copy(
        generation = session?.generation, origin = session?.origin.orEmpty(), username = session?.username.orEmpty(),
        pending = null, records = emptyList(),
        updates = if (generation == session?.generation) updates else emptyList(),
        offeredModel = offeredModel.takeIf { generation == session?.generation }
    )

    fun withFeed(sessionGeneration: String, releases: List<UpdateRelease>, model: UpdateRelease?): SyncUiState =
        if (generation == sessionGeneration) copy(updates = releases, offeredModel = model) else this
}

/** Activity-scoped owner of sync operations; Compose never executes network or queue writes. */
class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val store = SyncSessionStore.get(context)
    private val database = JournalDatabase.get(context)
    private val updateStore = KnowledgeUpdates.store(context)
    private val mutable = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = mutable
    private val notices = Channel<SyncNotice>(Channel.BUFFERED)
    val events = notices.receiveAsFlow()
    @Volatile private var operation = 0L
    private var job: Job? = null
    private var taskKind: String? = null
    private var screen: String? = null

    init {
        viewModelScope.launch {
            val (active, models) = withContext(Dispatchers.IO) {
                updateStore.available("kb").firstOrNull() to ModelUpdates.get(context).also { it.load() }
            }
            mutable.update { it.copy(activeKnowledge = active) }
            models.state.filterNotNull().collect { selected ->
                mutable.update { it.copy(activeModel = selected.release, modelNotice = selected.notice,
                    pendingModelRestart = models.pending != null) }
                selected.notice?.let { announce(it) }
            }
        }
        viewModelScope.launch {
            store.session.collectLatest { session ->
                mutable.update { it.forSession(session) }
                if (session != null) database.sync().observeAll(session.account).collect { records ->
                    mutable.update { it.copy(records = records) }
                }
            }
        }
        viewModelScope.launch {
            store.authenticationRequired.collect { required ->
                mutable.update { it.copy(authenticationRequired = required) }
                if (required) announce("Сеанс истёк или доступ запрещён. Войдите заново в настройках синхронизации. Осмотры сохранены.", true)
            }
        }
    }

    fun screenChanged(route: String) {
        if (screen != null && screen != route) {
            cancelConfirmation()
            if (screen == "settings" && taskKind == "login") {
                operation++
                job?.cancel()
                taskKind = null
                mutable.update { it.copy(busy = false) }
            }
        }
        screen = route
    }

    fun login(address: String, username: String, password: String) {
        if (state.value.busy) return
        val attempt = store.beginLogin()
        task("login") { alive ->
            val active = { alive() && store.loginIsCurrent(attempt) }
            val origin = SyncEndpoint.normalize(address)
            val token = SyncHttpClient(origin, active = active).login(username.trim(), password).getString("access_token")
            val profile = SyncHttpClient(origin, token, active).profile()
            val session = SyncSession(origin, profile.getString("id"), profile.getString("username"), token)
            if (!store.completeLogin(attempt, session, active) { SyncWork.request(context) }) throw CancellationException("Stale login")
            "Вход выполнен. Выберите осмотр для передачи."
        }
    }

    fun send(id: String) = withSession("send") { session, active ->
        checkActive(active)
        SyncWork.engine(context).enqueue(session.account, id) { write ->
            store.commitQueue(session.generation, active, write)
        }
        checkActive(active)
        SyncWork.request(context)
        "Осмотр поставлен в очередь. Отправка завершится после передачи записи и всех фото."
    }

    fun retry() = withSession("retry") { _, active ->
        checkActive(active)
        SyncWork.request(context)
        "Повтор выбранных осмотров запланирован. Требуется подключение к сети."
    }

    fun receive() = withSession("receive") { session, active ->
        SyncImporter(database, File(context.getExternalFilesDir(null) ?: context.filesDir, "photos"), File(context.cacheDir, "sync-download"))
            .importAll(session.account, SyncHttpClient(session.origin, session.token, active), active).summary()
    }

    fun askAll() = withSession("ask-all") { session, active ->
        val targets = database.journal().inspections().map { SyncTarget(it.inspection.id, it.inspection.updatedAt) }
        require(targets.isNotEmpty()) { "В журнале нет осмотров для отправки." }
        withContext(Dispatchers.Main.immediate) {
            checkActive(active)
            mutable.update { it.copy(pending = SyncConfirmation.All(session.generation, targets)) }
        }
        "Передать на ${session.origin} все выбранные осмотры: ${targets.size}, включая фото, заметки и координаты? Подтвердите или скажите «отмена»."
    }

    fun askConflict(id: String) = withSession("ask-conflict") { session, active ->
        val record = database.sync().record(session.account, id)
        require(record?.state == "conflict" && record.conflictJson != null) { "У этого осмотра нет сохранённого конфликта." }
        val local = requireNotNull(database.journal().inspection(id)) { "Осмотр не найден." }
        withContext(Dispatchers.Main.immediate) {
            checkActive(active)
            mutable.update { it.copy(pending = SyncConfirmation.Conflict(session.generation, SyncTarget(id, local.updatedAt), record.conflictJson)) }
        }
        "Передать текущий локальный вариант поверх показанной серверной версии? Подтвердите или скажите «отмена»."
    }

    fun confirm(expected: SyncConfirmation) {
        if (state.value.pending != expected || store.session.value?.generation != expected.generation) {
            announce("Подтверждение устарело. Запросите его заново.", true)
            return
        }
        mutable.update { it.copy(pending = null) } // Consume once before any asynchronous work.
        withSession("confirm") { session, active ->
            check(session.generation == expected.generation) { "Аккаунт изменился. Запросите подтверждение заново." }
            val engine = SyncWork.engine(context)
            when (expected) {
                is SyncConfirmation.All -> {
                    check(expected.targets.all { database.journal().inspection(it.id)?.updatedAt == it.updatedAt }) {
                        "Журнал изменился после вопроса. Запросите отправку всех заново."
                    }
                    var queued = 0
                    val errors = mutableListOf<String>()
                    for (target in expected.targets) {
                        checkActive(active)
                        try {
                            engine.enqueue(session.account, target.id, target.updatedAt) { write ->
                                store.commitQueue(session.generation, active, write)
                            }
                            queued++
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { errors += syncError(error) }
                    }
                    checkActive(active)
                    if (queued > 0) SyncWork.request(context)
                    "Поставлено в очередь: $queued. Не поставлено: ${errors.size}." + (errors.firstOrNull()?.let { " $it" } ?: " Отправка ещё не завершена.")
                }
                is SyncConfirmation.Conflict -> {
                    checkActive(active)
                    engine.resolveConflict(session.account, expected.target.id, expected.serverJson, expected.target.updatedAt) { write ->
                        store.commitQueue(session.generation, active, write)
                    }
                    checkActive(active)
                    SyncWork.request(context)
                    "Локальный вариант поставлен в очередь. Новая серверная правка вызовет новый конфликт."
                }
            }
        }
    }

    fun checkUpdates() = withSession("updates") { session, active ->
        val releases = SyncHttpClient(session.origin, session.token, active).updates()
        val models = ModelUpdates.get(context)
        val offered = models.offered(releases)
        checkActive { active() && state.value.generation == session.generation }
        mutable.update { if (active()) it.withFeed(session.generation, releases, offered) else it }
        checkActive { active() && state.value.generation == session.generation }
        KnowledgeUpdates.describe(releases, state.value.activeKnowledge) + " " + when {
            offered != null -> "Доступна модель ${offered.version}. Скажите «установи обновление модели» или нажмите кнопку. Она начнёт работать после полного перезапуска приложения."
            models.pending != null -> "Модель установлена и начнёт работать после полного перезапуска приложения."
            else -> "Новых совместимых моделей нет."
        }
    }

    fun installModel() = withSession("install-model") { session, active ->
        checkActive { active() && state.value.generation == session.generation }
        val release = state.value.offeredModel ?: return@withSession "Сначала проверьте обновления: совместимой новой модели нет."
        val models = ModelUpdates.get(context)
        models.compatible(release)
        SyncHttpClient(session.origin, session.token, active).downloadUpdate(release, models.store)
        checkActive(active)
        models.install(release, active) { activate -> store.commitQueue(session.generation, active, activate) }
        mutable.update { it.copy(offeredModel = null, activeModel = models.load().release,
            pendingModelRestart = models.pending != null) }
        "Модель ${release.version} установлена. Она начнёт работать после полного перезапуска приложения. Текущий анализ и сохранённые осмотры не изменены."
    }

    /** Download → SHA → format check → atomic switch; the previous book stays available for rollback. */
    fun installKnowledge() = withSession("install-kb") { session, active ->
        checkActive { active() && state.value.generation == session.generation }
        val release = state.value.offeredKnowledge ?: return@withSession "Сначала проверьте обновления: новой версии справочника нет."
        val file = SyncHttpClient(session.origin, session.token, active).downloadUpdate(release, updateStore)
        checkActive(active)
        try {
            com.agronomy.phytomonitor.knowledge.KnowledgeCatalog.parse(file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("Файл справочника не прошёл проверку формата. Используется прежняя версия.", error)
        }
        updateStore.activate(release)
        mutable.update { it.copy(activeKnowledge = release, knowledgeRevision = it.knowledgeRevision + 1) }
        "Справочник обновлён до версии ${release.version}. Прежняя версия сохранена. Карточки без проверки специалистом остаются черновиками."
    }

    fun cancelConfirmation() {
        mutable.update { it.copy(pending = null) }
        if (taskKind in setOf("ask-all", "ask-conflict")) {
            operation++
            job?.cancel()
            taskKind = null
            mutable.update { it.copy(busy = false) }
        }
    }

    fun logout() {
        cancelConfirmation()
        task("logout", replace = true) {
            store.logout { SyncWork.cancel(context) }
            "Выход выполнен, отправка остановлена. Осмотры сохранены на устройстве."
        }
    }

    fun status() {
        val current = state.value
        val text = when {
            current.authenticationRequired -> "Сеанс истёк или доступ запрещён. Войдите заново в настройках синхронизации. Осмотры сохранены."
            current.generation == null -> "Синхронизация выключена. Осмотры хранятся на устройстве."
            else -> "Сервер ${current.origin}. " + if (current.records.isEmpty()) "Осмотры для передачи ещё не выбраны."
                else current.records.groupingBy { syncStateText(if (it.state == "sending" && it.account !in SyncWork.running.value) "queued" else it.state) }
                    .eachCount().entries.joinToString("; ") { "${it.key}: ${it.value}" }
        }
        announce(text + " " + if (current.pendingModelRestart) "Модель установлена; нужен полный перезапуск приложения."
            else "Доступное обновление модели: ${current.activeModel?.version ?: "нет, доступен встроенный каталог"}.")
    }

    private fun withSession(kind: String, action: suspend (SyncSession, () -> Boolean) -> String) {
        val session = store.session.value
        if (session == null) { announce("Войдите на сервер в настройках синхронизации. Осмотры сохранены.", true); return }
        task(kind) { alive ->
            val active = { alive() && store.session.value?.generation == session.generation }
            try { action(session, active) }
            catch (error: SyncHttpException) {
                if (error.status in setOf(401, 403)) store.expire(session.generation) { SyncWork.cancel(context) }
                throw error
            }
        }
    }

    private fun task(kind: String, replace: Boolean = false, action: suspend (() -> Boolean) -> String) {
        if (state.value.busy && !replace) { announce("Подождите завершения текущей операции.", true); return }
        val ticket = ++operation
        if (replace) job?.cancel()
        taskKind = kind
        mutable.update { it.copy(busy = true) }
        job = viewModelScope.launch {
            val lifecycle = currentCoroutineContext()
            try {
                val text = withContext(Dispatchers.IO) { action { lifecycle.isActive && operation == ticket } }
                if (operation == ticket) announce(text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (operation == ticket) announce(syncError(error), true) }
            finally {
                if (operation == ticket) { taskKind = null; mutable.update { it.copy(busy = false) } }
            }
        }
    }

    private fun announce(text: String, error: Boolean = false) {
        mutable.update { it.copy(message = text, messageIsError = error) }
        notices.trySend(SyncNotice(text, error))
    }
    private fun checkActive(active: () -> Boolean) { if (!active()) throw CancellationException("Sync action ended") }
}

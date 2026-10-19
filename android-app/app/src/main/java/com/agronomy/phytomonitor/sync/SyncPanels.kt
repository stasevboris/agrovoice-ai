package com.agronomy.phytomonitor.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.journal.JournalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun SyncSettingsPanel(initialServer: String) {
    val context = LocalContext.current.applicationContext
    val store = remember { SyncSessionStore.get(context) }
    val session by store.session.collectAsState()
    val running by SyncWork.running.collectAsState()
    var server by rememberSaveable { mutableStateOf(session?.origin ?: initialServer.ifBlank { "http://127.0.0.1:8027" }) }
    var username by rememberSaveable { mutableStateOf(session?.username.orEmpty()) }
    // Intentionally not rememberSaveable: passwords must not enter the saved-instance-state bundle.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun act(action: (() -> Boolean) -> String) {
        scope.launch {
            busy = true
            val lifecycle = currentCoroutineContext()
            try {
                message = withContext(Dispatchers.IO) { action { lifecycle.isActive } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = syncError(error)
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Синхронизация", style = MaterialTheme.typography.titleMedium)
        Text("Осмотры остаются на устройстве. Передаются только выбранные осмотры; их последующие изменения отправляются автоматически.")
        OutlinedTextField(server, { server = it }, label = { Text("Адрес сервера") }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        if (server.trim().startsWith("http://", ignoreCase = true)) {
            Text("HTTP не шифрует пароль, токен и фото. Для сети используйте доверенный локальный сервер; предпочтителен HTTPS.",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true, enabled = !busy)
        OutlinedTextField(password, { password = it }, label = { Text("Пароль (не сохраняется)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
        Button(enabled = !busy && username.isNotBlank() && password.isNotEmpty(), onClick = {
            val address = server
            val login = username.trim()
            val secret = password
            password = ""
            val attempt = store.beginLogin()
            act { screenActive ->
                val active = { screenActive() && store.loginIsCurrent(attempt) }
                val origin = SyncEndpoint.normalize(address)
                val token = SyncHttpClient(origin, active = active).login(login, secret).getString("access_token")
                val profile = SyncHttpClient(origin, token, active).profile()
                val authenticated = SyncSession(origin, profile.getString("id"), profile.getString("username"), token)
                if (!store.completeLogin(attempt, authenticated, active) { SyncWork.request(context) }) {
                    throw CancellationException("Login attempt superseded or screen disposed")
                }
                "Вход выполнен. Выберите осмотр в журнале или отправьте все записи этой кнопкой."
            }
        }) { Text(if (busy) "Подождите…" else "Войти") }
        session?.let { current ->
            Text("Подключено: ${current.username} — ${current.origin}")
            val dao = remember { JournalDatabase.get(context).sync() }
            val records by remember(current.account) { dao.observeAll(current.account) }.collectAsState(initial = emptyList())
            if (records.isNotEmpty()) {
                Text(records.groupingBy { syncStateText(if (it.state == "sending" && current.account !in running) "queued" else it.state) }
                    .eachCount().entries.joinToString("; ") { "${it.key}: ${it.value}" })
                records.firstOrNull { it.error != null }?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Button(enabled = !busy, onClick = { confirmAll = true }) { Text("Отправить все осмотры") }
            Button(enabled = !busy, onClick = {
                SyncWork.request(context)
                message = "Повтор запланирован для выбранных осмотров. Требуется доступ к сети."
            }) { Text("Повторить и обновить статусы") }
            Button(enabled = !busy, onClick = {
                act { screenActive ->
                    val folder = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "photos")
                    val importer = SyncImporter(JournalDatabase.get(context), folder, java.io.File(context.cacheDir, "sync-download"))
                    val active = { screenActive() && store.session.value?.generation == current.generation }
                    val report = importer.importAll(current.account, SyncHttpClient(current.origin, current.token, active), active)
                    report.summary() + " Откройте журнал, чтобы увидеть полученные осмотры."
                }
            }) { Text("Получить новые осмотры сервера") }
            TextButton(enabled = !busy, onClick = {
                act {
                    store.logout { SyncWork.cancel(context) }
                    "Выход выполнен. Локальные осмотры и серверные копии сохранены."
                }
            }) { Text("Выйти и остановить отправку") }
        }
        if (session == null) Text("Синхронизация выключена. Для отправки войдите на локальный сервер.")
        message?.let { Text(it) }
    }
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false },
        title = { Text("Отправить все осмотры?") },
        text = { Text("Все записи журнала, заметки, координаты и фото будут переданы на ${session?.origin}. Новые осмотры выбираются отдельно.") },
        confirmButton = { TextButton(onClick = {
            confirmAll = false
            val current = session ?: return@TextButton
            act {
                val engine = SyncWork.engine(context)
                val records = JournalDatabase.get(context).journal().inspections()
                var added = 0
                var skipped = 0
                for (record in records) {
                    if (runCatching { engine.enqueue(current.account, record.inspection.id) }.isSuccess) added++ else skipped++
                }
                SyncWork.request(context)
                "Выбрано: $added. Не готовы к отправке: $skipped (откройте осмотр для объяснения). Все данные сохранены локально."
            }
        }) { Text("Отправить") } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Отмена") } })
}

@Composable
fun InspectionSyncPanel(id: String, updatedAt: Long) {
    val context = LocalContext.current.applicationContext
    val store = remember { SyncSessionStore.get(context) }
    val session by store.session.collectAsState()
    val running by SyncWork.running.collectAsState()
    val dao = remember { JournalDatabase.get(context).sync() }
    val current = session
    val record by remember(current?.account, id) { dao.observe(current?.account.orEmpty(), id) }.collectAsState(initial = null)
    var message by remember(id) { mutableStateOf<String?>(null) }
    var conflict by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun enqueue(resolve: String? = null) {
        if (current == null) return
        scope.launch {
            busy = true
            message = try {
                withContext(Dispatchers.IO) {
                    val engine = SyncWork.engine(context)
                    if (resolve == null) engine.enqueue(current.account, id) else engine.resolveConflict(current.account, id, resolve)
                    SyncWork.request(context)
                }
                "Отправка запланирована. Осмотр доступен на устройстве."
            } catch (error: Exception) { syncError(error) }
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val state = record?.let {
            when {
                it.state == "synced" && it.snapshotUpdatedAt != updatedAt -> "queued"
                it.state == "sending" && current?.account !in running -> "queued"
                else -> it.state
            }
        } ?: "local"
        Text("Синхронизация: ${syncStateText(state)}", style = MaterialTheme.typography.titleSmall)
        record?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        record?.let {
            val expert = when (it.expertStatus) {
                "confirmed" -> "подтверждено специалистом"
                "error" -> "специалист отметил ошибку модели"
                else -> "не проверено специалистом"
            }
            Text("Серверная отметка: $expert")
        }
        if (current == null) {
            Text("Вход на локальный сервер — в настройках. Осмотр и фото сохранены на устройстве.")
        } else if (state == "conflict") {
            Text("На сервере другая версия. Локальная запись сохранена; автоматическая отправка остановлена.")
            Button(onClick = { conflict = record?.conflictJson }, enabled = !busy) { Text("Разобрать конфликт") }
        } else {
            Button(onClick = { enqueue() }, enabled = !busy && state != "sending") {
                Text(if (state == "local") "Отправить осмотр и фото" else "Повторить отправку")
            }
        }
        message?.let { Text(it) }
    }
    conflict?.let { seen ->
        val remote = runCatching { JSONObject(seen).optJSONObject("inspection") }.getOrNull()
        AlertDialog(onDismissRequest = { conflict = null }, title = { Text("Конфликт версий") },
            text = { Text("Серверная версия: ${remote?.optLong("version") ?: "недоступна"}\n" +
                "Серверная заметка: ${remote?.optJSONObject("data")?.optString("note").orEmpty()}\n" +
                "Отправить текущий локальный осмотр поверх этой версии? Если сервер изменится снова, появится новый конфликт.") },
            confirmButton = { TextButton(enabled = remote != null, onClick = { conflict = null; enqueue(seen) }) { Text("Отправить локальный вариант") } },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Оставить обе версии") } })
    }
}

fun syncStateText(state: String): String = when (state) {
    "queued" -> "ожидает отправки"
    "sending" -> "отправляется"
    "synced" -> "синхронизирован с фото"
    "error" -> "ошибка отправки"
    "conflict" -> "конфликт версий"
    else -> "сохранён на устройстве"
}

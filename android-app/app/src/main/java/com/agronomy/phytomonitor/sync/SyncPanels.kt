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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.agronomy.phytomonitor.actions.SyncConfirmation
import com.agronomy.phytomonitor.voice.VoiceAction
import org.json.JSONObject

@Composable
fun SyncSettingsPanel(model: SyncViewModel, initialServer: String, onAction: (VoiceAction) -> Unit) {
    val state by model.state.collectAsState()
    val running by SyncWork.running.collectAsState()
    var server by rememberSaveable(state.origin) { mutableStateOf(state.origin.ifBlank { initialServer.ifBlank { "http://127.0.0.1:8027" } }) }
    var username by rememberSaveable(state.username) { mutableStateOf(state.username) }
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Синхронизация", style = MaterialTheme.typography.titleMedium)
        Text("Передаются только выбранные осмотры и их последующие изменения. Новые осмотры выбираются отдельно.")
        OutlinedTextField(server, { server = it }, label = { Text("Адрес сервера") }, singleLine = true,
            enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        if (server.trim().startsWith("http://", ignoreCase = true)) Text(
            "HTTP не шифрует пароль, токен и фото. Используйте доверенный локальный сервер; предпочтителен HTTPS.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true, enabled = !state.busy)
        OutlinedTextField(password, { password = it }, label = { Text("Пароль (не сохраняется)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.busy)
        Button(enabled = !state.busy && username.isNotBlank() && password.isNotEmpty(), onClick = {
            val secret = password
            password = ""
            model.login(server, username, secret)
        }) { Text(if (state.busy) "Подождите…" else "Войти") }
        if (state.authenticationRequired) Text("Сеанс истёк или доступ запрещён. Войдите заново; отправка остановлена.", color = MaterialTheme.colorScheme.error)
        if (state.generation != null) {
            Text("Подключено: ${state.username} — ${state.origin}")
            if (state.records.isNotEmpty()) Text(state.records.groupingBy { syncStateText(if (it.state == "sending" && it.account !in running) "queued" else it.state) }.eachCount().entries
                .joinToString("; ") { "${it.key}: ${it.value}" })
            state.records.firstOrNull { it.error != null }?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !state.busy, onClick = { onAction(VoiceAction.SYNC_ALL) }) { Text("Отправить все осмотры") }
            Button(enabled = !state.busy, onClick = { onAction(VoiceAction.SYNC_RETRY) }) { Text("Повторить и обновить статусы") }
            Button(enabled = !state.busy, onClick = { onAction(VoiceAction.SYNC_RECEIVE) }) { Text("Получить новые осмотры сервера") }
            Text("Справочник: " + (state.activeKnowledge?.let { "обновление ${it.version}" } ?: "встроенная версия"))
            TextButton(enabled = !state.busy, onClick = { onAction(VoiceAction.UPDATES_CHECK) }) { Text("Проверить обновления") }
            state.offeredKnowledge?.let { release ->
                Button(enabled = !state.busy, onClick = { onAction(VoiceAction.UPDATES_INSTALL) }) {
                    Text("Установить справочник ${release.version}")
                }
            }
        } else Text("Синхронизация выключена. Осмотры доступны на устройстве.")
        TextButton(onClick = { onAction(VoiceAction.SYNC_STATUS) }) { Text("Прочитать состояние синхронизации") }
        if (state.generation != null || state.busy) TextButton(onClick = { onAction(VoiceAction.SYNC_LOGOUT) }) { Text("Выйти и остановить отправку") }
        state.message?.let { Text(it, color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
fun InspectionSyncPanel(model: SyncViewModel, id: String, updatedAt: Long, onAction: (VoiceAction) -> Unit) {
    val ui by model.state.collectAsState()
    val running by SyncWork.running.collectAsState()
    val record = ui.records.firstOrNull { it.localId == id }
    val state = when {
        record == null -> "local"
        record.state == "synced" && record.snapshotUpdatedAt != updatedAt -> "queued"
        record.state == "sending" && record.account !in running -> "queued"
        else -> record.state
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Синхронизация: ${syncStateText(state)}", style = MaterialTheme.typography.titleSmall)
        record?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        record?.let { Text((if (it.snapshotUpdatedAt == updatedAt && state == "synced") "Серверная отметка: " else "Отметка ранее отправленной версии: ") + when (it.expertStatus) {
            "confirmed" -> "подтверждено специалистом"
            "error" -> "специалист отметил ошибку модели"
            else -> "не проверено специалистом"
        })
            if (it.snapshotUpdatedAt != updatedAt || state != "synced") Text("Текущие локальные изменения ещё не проверены специалистом.")
        }
        if (ui.generation == null) {
            Text(if (ui.authenticationRequired) "Для отправки войдите заново. Осмотр и фото сохранены." else "Вход на сервер — в настройках синхронизации.")
            TextButton(onClick = { onAction(VoiceAction.SYNC_SETTINGS) }) { Text("Открыть синхронизацию") }
        } else if (state == "conflict") {
            Text("На сервере другая версия. Автоматическая передача остановлена; локальная запись сохранена.")
            Button(enabled = !ui.busy, onClick = { onAction(VoiceAction.SYNC_CONFLICT) }) { Text("Разобрать конфликт") }
        } else Button(enabled = !ui.busy && state != "sending", onClick = { onAction(VoiceAction.SYNC_CURRENT) }) {
            Text(if (state == "local") "Отправить осмотр и фото" else "Повторить отправку")
        }
        ui.message?.let { Text(it) }
    }
}

@Composable
fun SyncConfirmationDialog(pending: SyncConfirmation?, onConfirm: () -> Unit, onCancel: () -> Unit, busy: Boolean, origin: String) {
    if (pending == null) return
    val description = when (pending) {
        is SyncConfirmation.All -> "Передать выбранные осмотры (${pending.targets.size}), их фото, заметки и координаты? Изменённые после вопроса записи потребуют нового подтверждения."
        is SyncConfirmation.Conflict -> {
            val remote = runCatching { JSONObject(pending.serverJson).optJSONObject("inspection") }.getOrNull()
            "Серверная версия: ${remote?.optLong("version") ?: "недоступна"}\nСерверная заметка: ${remote?.optJSONObject("data")?.optString("note").orEmpty()}\nПередать текущий локальный вариант поверх этой версии?"
        }
    }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Подтвердить передачу") }, text = { Text("Сервер: $origin\n$description") },
        confirmButton = { TextButton(enabled = !busy, onClick = onConfirm) { Text("Подтвердить передачу") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } })
}

fun syncStateText(state: String): String = when (state) {
    "queued" -> "ожидает отправки"
    "sending" -> "отправляется"
    "synced" -> "синхронизирован с фото"
    "error" -> "ошибка отправки"
    "conflict" -> "конфликт версий"
    else -> "сохранён на устройстве"
}

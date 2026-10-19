package com.agronomy.phytomonitor.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(model: SettingsViewModel, onBack: () -> Unit) {
    val settings by model.state.collectAsState()
    val error by model.error.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("Назад") }
        Text("Настройки приложения", style = MaterialTheme.typography.headlineSmall)
        val current = settings
        if (current == null) {
            Text(error ?: "Загрузка настроек…")
        } else {
            SettingToggle("Голосовое управление", "Команды через микрофон и гарнитуру", current.voiceEnabled, model::voiceEnabled)
            SettingToggle("Озвучка после фото", "Прочитать результат и спросить о сохранении после голосового снимка", current.spokenFollowUp, model::spokenFollowUp)
            SettingToggle("Свободная речь (эксперимент)", "Обычные формулировки команд; на речи людей пока не проверено", current.freeSpeech, model::freeSpeech)
            Text("Выбор модели, культуры и порога — в настройках анализа на экране осмотра.")
            Text("Синхронизация", style = MaterialTheme.typography.titleMedium)
            var server by rememberSaveable(current.server) { mutableStateOf(current.server) }
            OutlinedTextField(server, { server = it }, label = { Text("Адрес сервера") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { model.server(server) }, enabled = server.trim() != current.server) { Text("Сохранить адрес") }
            Text("Подключение к серверу пока недоступно. Осмотры хранятся на телефоне.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SettingToggle(label: String, description: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, role = Role.Switch, onValueChange = change).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onCheckedChange = null)
    }
}

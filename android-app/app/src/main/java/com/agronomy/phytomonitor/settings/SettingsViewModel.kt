package com.agronomy.phytomonitor.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore.get(application)
    val state = store.state
    val error = store.error
    fun voiceEnabled(value: Boolean) = store.set(Keys.voiceEnabled, value)
    fun spokenFollowUp(value: Boolean) = store.set(Keys.spokenFollowUp, value)
    fun freeSpeech(value: Boolean) = store.set(Keys.freeSpeech, value)
    fun server(value: String) = store.set(Keys.server, value.trim().take(500))
}

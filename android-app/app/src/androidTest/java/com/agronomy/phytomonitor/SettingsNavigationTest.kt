package com.agronomy.phytomonitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test
    fun settingsSurviveRecreationAndDisabledVoiceCannotOpenCamera() {
        compose.waitUntil(60000) { !compose.activity.fieldModel.busy }
        val store = SettingsStore.get(compose.activity)
        val before = requireNotNull(store.state.value)
        try {
            compose.onNodeWithText("Настройки приложения").performClick()
            compose.waitUntil(10000) { compose.activity.currentState().screen == Screen.SETTINGS }
            compose.onNodeWithText("Озвучка после фото").performClick()
            compose.waitUntil(10000) { store.state.value?.spokenFollowUp == !before.spokenFollowUp }
            compose.runOnUiThread { compose.activity.settingsModel.voiceEnabled(false) }
            compose.waitUntil(10000) { store.state.value?.voiceEnabled == false }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30000) { !compose.activity.fieldModel.busy }
            assertEquals(Screen.SETTINGS, compose.activity.currentState().screen)
            assertEquals(!before.spokenFollowUp, store.state.value!!.spokenFollowUp)
            assertFalse(store.state.value!!.voiceEnabled)
            assertEquals(before.model, compose.activity.fieldModel.selectedModel)
            assertEquals(before.threshold, compose.activity.fieldModel.threshold)
            assertEquals(before.plot, compose.activity.fieldModel.plotName)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10000) { compose.activity.currentState().screen == Screen.MAIN }
            compose.runOnUiThread { compose.activity.handleVoiceCommand(VoiceCommands.parse("открой камеру")!!) }
            assertFalse((compose.activity.currentState().screen == com.agronomy.phytomonitor.actions.Screen.CAMERA))
            assertFalse(compose.activity.voiceListening)
        } finally {
            runBlocking {
                store.set(Keys.voiceEnabled, before.voiceEnabled).join()
                store.set(Keys.spokenFollowUp, before.spokenFollowUp).join()
                store.set(Keys.continuous, before.continuous).join()
            }
            compose.waitUntil(10000) { store.state.value == before }
        }
    }
}

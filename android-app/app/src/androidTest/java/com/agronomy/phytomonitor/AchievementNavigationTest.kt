package com.agronomy.phytomonitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AchievementNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test fun disablingAchievementsSurvivesRecreationAndKeepsEarnedBadges() {
        compose.waitUntil(60000) { !compose.activity.currentState().busy }
        val settings = SettingsStore.get(compose.activity)
        val original = requireNotNull(settings.state.value).achievements
        val dao = JournalDatabase.get(compose.activity).journal()
        val earned = dao.achievements()
        try {
            compose.runOnUiThread { compose.activity.handleVoiceCommand(VoiceCommands.parse("открой настройки")!!) }
            compose.onNodeWithText("Достижения").performScrollTo().performClick()
            compose.waitUntil(10000) { settings.state.value?.achievements == !original }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30000) { !compose.activity.currentState().busy }
            assertEquals(!original, settings.state.value!!.achievements)
            assertEquals(earned, dao.achievements())
        } finally {
            runBlocking { settings.set(Keys.achievements, original).join() }
        }
    }
}

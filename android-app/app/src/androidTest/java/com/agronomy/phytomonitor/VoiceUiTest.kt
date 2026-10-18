package com.agronomy.phytomonitor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

class VoiceUiTest {
    @get:Rule val compose=createAndroidComposeRule<FieldDemoActivity>()
    @Test fun microphoneStartsStopsAndCommandHelpIsAvailable() {
        compose.waitUntil(20000){runCatching {compose.onNodeWithContentDescription("Голосовая команда").assertIsEnabled()}.isSuccess}
        compose.onNodeWithContentDescription("Голосовая команда").assertIsEnabled().performClick()
        compose.waitUntil(30000){compose.onAllNodesWithText("Слушаю. Скажи одну команду…").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Остановить микрофон").performClick()
        compose.onNodeWithContentDescription("Голосовая команда").assertIsEnabled()
        compose.onNodeWithText("Сделать фото").assertIsEnabled()
        compose.onNodeWithText("Фразы").performClick()
        compose.onNodeWithText("Голосовые команды").assertIsDisplayed()
        compose.onNodeWithText("Понятно").performClick()
        compose.onNodeWithContentDescription("Голосовая команда").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("Слушаю. Скажи одну команду…").fetchSemanticsNodes().isNotEmpty()}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithContentDescription("Голосовая команда").assertIsEnabled()
        compose.onNodeWithText("Сделать фото").assertIsEnabled()
    }
}

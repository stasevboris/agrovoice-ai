package com.agronomy.phytomonitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KnowledgeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test
    fun bundledDraftCanBeFoundOfflineAndSurvivesRecreationWithoutChangingAnalysis() {
        compose.waitUntil(60000) { !compose.activity.fieldModel.busy && !compose.activity.knowledgeModel.state.value.loading }
        val crop = compose.activity.fieldModel.crop
        val photo = compose.activity.fieldModel.photo
        compose.onNodeWithText("Справочник").performClick()
        compose.runOnUiThread { compose.activity.handleVoiceCommand(VoiceCommands.parse("картофель")!!) }
        compose.onNodeWithText("Поиск по справочнику").performTextInput("фитофтороз")
        compose.runOnUiThread { compose.activity.handleVoiceCommand(VoiceCommands.parseForScreen("карточка один", "knowledge")!!) }
        compose.waitUntil(10000) { compose.activity.currentState().knowledgeCardOpen }
        compose.waitUntil(10000) { compose.activity.knowledgeModel.state.value.selected != null }
        assertFalse(compose.activity.currentState().verifiedKnowledge)
        assertTrue(compose.activity.knowledgeModel.spoken().contains("не проверена"))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30000) { compose.activity.knowledgeModel.state.value.selected != null && !compose.activity.fieldModel.busy }
        assertEquals("potato__late_blight", compose.activity.knowledgeModel.state.value.selected!!.code)
        assertEquals(crop, compose.activity.fieldModel.crop)
        assertEquals(photo, compose.activity.fieldModel.photo)
        assertNull(compose.activity.fieldModel.result)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10000) { !compose.activity.currentState().knowledgeCardOpen }
        assertEquals(Screen.KNOWLEDGE, compose.activity.currentState().screen)
        compose.runOnUiThread { compose.activity.handleVoiceCommand(VoiceCommands.parse("назад")!!) }
        assertEquals(Screen.MAIN, compose.activity.currentState().screen)
    }
}

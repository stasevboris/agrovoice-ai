package com.agronomy.phytomonitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class JournalNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test
    fun savedInspectionSurvivesRecreationAndBackReturnsThroughJournalWithoutAnalysis() {
        compose.waitUntil(60000) { !compose.activity.fieldModel.busy && !compose.activity.journalModel.state.value.busy }
        val journal = InspectionJournal(compose.activity)
        val records = journal.list().associate { it.id to journal.record(it.id).toString() }
        assertTrue("Device fixture needs an existing saved inspection", records.isNotEmpty())
        val resultFolder = File(compose.activity.getExternalFilesDir(null), "results")
        val reports = resultFolder.list()?.toSet().orEmpty()
        compose.runOnUiThread {
            compose.activity.handleVoiceCommand(VoiceCommands.parse("открой журнал")!!)
        }
        compose.waitUntil(30000) {
            compose.activity.journalModel.state.value.entries.isNotEmpty() && !compose.activity.currentState().busy
        }
        compose.runOnUiThread {
            compose.activity.handleVoiceCommand(VoiceCommands.parseForScreen("открой осмотр один", "journal")!!)
        }
        compose.waitUntil(30000) { compose.activity.journalModel.state.value.opened != null }
        val selected = compose.activity.journalModel.state.value.opened!!.details.inspection.id
        assertTrue(compose.activity.currentState().inspectionOpen)
        assertNull(compose.activity.fieldModel.result)
        compose.runOnUiThread {
            compose.activity.handleVoiceCommand(VoiceCommands.parseForScreen("прочитай результат", "journal")!!)
        }

        compose.activityRule.scenario.recreate()
        compose.waitUntil(30000) { compose.activity.journalModel.state.value.opened != null }
        assertEquals(selected, compose.activity.journalModel.state.value.opened!!.details.inspection.id)
        assertEquals(Screen.JOURNAL, compose.activity.currentState().screen)
        assertTrue(compose.activity.currentState().inspectionOpen)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10000) { !compose.activity.currentState().inspectionOpen }
        assertEquals(Screen.JOURNAL, compose.activity.currentState().screen)
        compose.runOnUiThread { compose.activity.handleVoiceCommand(VoiceCommands.parse("назад")!!) }
        compose.waitUntil(10000) { compose.activity.currentState().screen == Screen.MAIN }
        assertFalse(compose.activity.currentState().inspectionOpen)
        assertNull(compose.activity.fieldModel.result)
        assertEquals(reports, resultFolder.list()?.toSet().orEmpty())
        assertEquals(records, journal.list().associate { it.id to journal.record(it.id).toString() })
    }
}

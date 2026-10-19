package com.agronomy.phytomonitor

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.journal.FieldPlots
import com.agronomy.phytomonitor.journal.JournalDatabase
import com.agronomy.phytomonitor.settings.Keys
import com.agronomy.phytomonitor.settings.SettingsStore
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand
import com.agronomy.phytomonitor.voice.VoiceCommands
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class PlotsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<FieldDemoActivity>()

    @Test
    fun roomChangesRefreshVoiceChoicesAndCurrentPlotWithoutReopeningScreen() {
        compose.waitUntil(60000) { !compose.activity.fieldModel.busy }
        val activity = compose.activity
        val original = activity.fieldModel.plotName
        val name = "TEST: nav-${UUID.randomUUID()}"
        var id: String? = null
        val journal = InspectionJournal(activity)
        val before = journal.list().associate { it.id to journal.record(it.id).toString() }
        try {
            compose.runOnUiThread { activity.handleVoiceCommand(VoiceCommands.parse("открой участки")!!) }
            assertEquals(Screen.PLOTS, activity.currentState().screen)
            compose.onNodeWithText("Новый участок").performTextInput(name)
            compose.onNodeWithText("Добавить").performClick()
            compose.waitUntil(10000) { activity.plotsModel.state.value.plots.any { it.plot.name == name } }
            id = activity.plotsModel.state.value.plots.first { it.plot.name == name }.plot.id
            assertTrue(name in activity.currentState().plots)
            compose.runOnUiThread { activity.handleVoiceCommand(VoiceCommand(VoiceAction.PLOT, "", name)) }
            assertEquals(name, activity.fieldModel.plotName)
            compose.runOnUiThread { activity.plotsModel.renamePlot(id!!, "$name renamed") }
            compose.waitUntil(10000) { activity.fieldModel.plotName == "$name renamed" && "$name renamed" in activity.currentState().plots }
            assertTrue("$name renamed" in activity.currentState().plots)
            compose.runOnUiThread { activity.plotsModel.deletePlot(id!!) }
            compose.waitUntil(10000) {
                activity.fieldModel.plotName.isEmpty() && activity.plotsModel.state.value.plots.none { it.plot.id == id }
            }
            compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(Screen.MAIN, activity.currentState().screen)
            assertEquals(before, journal.list().associate { it.id to journal.record(it.id).toString() })
        } finally {
            val database = JournalDatabase.get(activity)
            val owned = id ?: database.journal().plotByName(name)?.id
            owned?.let { FieldPlots(database).delete(it) }
            runBlocking { SettingsStore.get(activity).set(Keys.plot, original).join() }
        }
    }
}

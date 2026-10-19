package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.actions.ActionPolicy
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.AppState
import com.agronomy.phytomonitor.actions.Operation
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.actions.Verdict
import com.agronomy.phytomonitor.location.LocationFix
import com.agronomy.phytomonitor.location.LocationFixes
import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotVoiceTest {
    private val plots = listOf("Теплица 2", "Ёлочный сад", "Поле у дороги")

    private fun voice(action: VoiceAction, value: String = "") = ActionRequest(action, value, ActionSource.VOICE)

    @Test
    fun rowIsANumberAfterTheWordRow() {
        assertEquals("14", VoiceCommands.parse("ряд четырнадцать")!!.value)
        assertEquals(VoiceAction.ROW, VoiceCommands.parse("Ряд 7")!!.action)
        assertEquals("120", VoiceCommands.parse("ряд сто двадцать")!!.value)
        listOf("ряд", "ряд тысяча", "ряд ноль", "второй ряд", "ряд четырнадцать и пятнадцать").forEach {
            assertNull(it, VoiceCommands.parse(it))
        }
    }

    @Test
    fun plotNameIsMatchedAsItIsSpoken() {
        assertEquals("теплица два", VoiceCommands.spokenPlot("Теплица 2"))
        assertEquals("елочный сад", VoiceCommands.spokenPlot("Ёлочный сад"))
        assertEquals("Теплица 2", VoiceCommands.parse("участок теплица два", plots)!!.value)
        assertEquals("Ёлочный сад", VoiceCommands.parse("Участок ёлочный сад", plots)!!.value)
        assertEquals("Поле у дороги", VoiceCommands.parse("участок поле у дороги", plots)!!.value)
        assertNull(VoiceCommands.parse("участок теплица три", plots))
        assertNull(VoiceCommands.parse("участок теплица два", emptyList()))
        assertNull(VoiceCommands.parse("не выбирай участок теплица два", plots))
    }

    @Test
    fun plotPhrasesWorkOnMainAndPlotScreensOnly() {
        assertEquals(VoiceAction.PLOT, VoiceCommands.parseForScreen("участок теплица два", "main", plots)!!.action)
        assertEquals(VoiceAction.ROW, VoiceCommands.parseForScreen("ряд три", "plots", plots)!!.action)
        assertNull(VoiceCommands.parseForScreen("ряд три", "camera", plots))
        assertNull(VoiceCommands.parseForScreen("участок теплица два", "note", plots))
        val grammar = VoiceCommands.mainGrammar(plots)
        assertTrue(grammar.contains("участок теплица два"))
        assertTrue(grammar.contains("ряд"))
        assertTrue(grammar.contains("сто девяносто девять"))
    }

    @Test
    fun policySelectsOnlyExistingPlotsAndValidRows() {
        val state = AppState(plots = plots, plot = "Поле у дороги", row = "3")
        assertEquals(
            listOf(Operation.SelectPlot("Теплица 2")),
            (ActionPolicy.resolve(voice(VoiceAction.PLOT, "теплица 2"), state) as Verdict.Run).operations
        )
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.PLOT, "Сад"), state) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.PLOT, "Поле у дороги"), state) is Verdict.Skip)
        assertEquals(listOf(Operation.SetRow("14")), (ActionPolicy.resolve(voice(VoiceAction.ROW, "14"), state) as Verdict.Run).operations)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.ROW, "3"), state) is Verdict.Skip)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.ROW, "1000"), state) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.ROW, "14"), state.copy(screen = Screen.CAMERA)) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.ROW, "14"), state.copy(busy = true)) is Verdict.Run)
    }

    @Test
    fun plotListOpensFromMainAndClosesWithBack() {
        assertEquals(listOf(Operation.OpenPlots), (ActionPolicy.resolve(voice(VoiceAction.PLOTS), AppState()) as Verdict.Run).operations)
        val open = AppState(screen = Screen.PLOTS, plots = plots)
        assertEquals(listOf(Operation.ClosePlots), (ActionPolicy.resolve(voice(VoiceAction.BACK), open) as Verdict.Run).operations)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.SAVE_INSPECTION), open.copy(hasResult = true)) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.PLOT, "Теплица 2"), open) is Verdict.Run)
    }

    @Test
    fun onlyAFreshFixIsAttachedToAnInspection() {
        val now = 1_000_000L
        val fix = LocationFix(53.9, 27.56, 8.0, now - 30_000, "gps")
        assertEquals(53.9, LocationFixes.forInspection(fix, now)!!.lat, 0.0)
        assertNull(LocationFixes.forInspection(fix.copy(timeMs = now - LocationFixes.MAX_AGE_MS - 1), now))
        assertNull(LocationFixes.forInspection(null, now))
        assertTrue(LocationFixes.describe(fix, now).startsWith("±8 м, 30 с назад"))
        assertTrue(LocationFixes.describe(fix.copy(timeMs = now - 600_000), now).contains("устарело"))
    }

    @Test
    fun newerFixWinsUnlessTheOldOneIsMuchMoreAccurateAndRecent() {
        val base = LocationFix(53.0, 27.0, 5.0, 100_000, "gps")
        val coarse = LocationFix(53.1, 27.1, 80.0, 110_000, "network")
        assertEquals(base, LocationFixes.better(base, coarse))
        val later = coarse.copy(timeMs = 200_000)
        assertEquals(later, LocationFixes.better(base, later))
        assertEquals(base, LocationFixes.better(null, base))
        assertEquals(base, LocationFixes.better(base, coarse.copy(timeMs = 10_000)))
    }
}

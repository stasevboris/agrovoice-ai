package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand
import com.agronomy.phytomonitor.voice.VoiceRouter
import org.junit.Assert.*
import org.junit.Test

class VoiceRouterTest {
    @Test
    fun settingsNavigationWorksWhileAnalysisRunsAndBackClosesOnlySettings() {
        val open = ActionRequest(VoiceAction.SETTINGS, source = ActionSource.BUTTON)
        assertEquals(Verdict.Run(listOf(Operation.OpenSettings)), ActionPolicy.resolve(open, AppState(busy = true)))
        val back = ActionRequest(VoiceAction.BACK, source = ActionSource.VOICE)
        assertEquals(Verdict.Run(listOf(Operation.CloseSettings)), ActionPolicy.resolve(back, AppState(screen = Screen.SETTINGS)))
        assertTrue(ActionPolicy.resolve(ActionRequest(VoiceAction.SAVE_INSPECTION, source = ActionSource.VOICE),
            AppState(screen = Screen.SETTINGS, hasResult = true)) is Verdict.Clarify)
    }

    @Test
    fun disabledSpokenFollowUpDoesNotCreateAnAutomaticQuestion() {
        val dialog = FollowUpDialog()
        val port = object : ActionPort {
            override fun state() = AppState(hasPhoto = true)
            override fun perform(operation: Operation) = Unit
            override fun tell(text: String, isError: Boolean, request: ActionRequest?) = Unit
        }
        val router = VoiceRouter({ Screen.MAIN }, { true }, ActionDispatcher(port), dialog, {}, {}, { false })
        assertTrue(router.command(VoiceCommand(VoiceAction.ANALYZE, "")) is Verdict.Run)
        assertEquals(FollowUpDialog.Phase.IDLE, dialog.phase)
    }

    @Test
    fun navigationIsReadForEachPhraseAndBackgroundCallbacksDoNothing() {
        var screen = Screen.MAIN
        var foreground = true
        val performed = mutableListOf<Operation>()
        lateinit var router: VoiceRouter
        val port = object : ActionPort {
            override fun state() = AppState(screen = router.screen, hasPhoto = true, hasResult = true)
            override fun perform(operation: Operation) { performed += operation }
            override fun tell(text: String, isError: Boolean, request: ActionRequest?) = Unit
        }
        router = VoiceRouter({ screen }, { foreground }, ActionDispatcher(port), FollowUpDialog(), {}, {})
        assertTrue(router.command(VoiceCommand(VoiceAction.SAVE_INSPECTION, "")) is Verdict.Run)
        screen = Screen.JOURNAL
        assertEquals(Screen.JOURNAL, router.screen)
        assertTrue(router.command(VoiceCommand(VoiceAction.ANALYZE, "")) is Verdict.Clarify)
        foreground = false
        assertNull(router.command(VoiceCommand(VoiceAction.BACK, "")))
        assertNull(router.answer("not json", "ignored background callback"))
        router.plan(listOf(VoiceCommand(VoiceAction.CAMERA, "")))
        assertEquals(listOf(Operation.SaveInspection), performed)
    }
}

package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceCommand
import com.agronomy.phytomonitor.voice.VoiceRouter
import org.junit.Assert.*
import org.junit.Test

class VoiceRouterTest {
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

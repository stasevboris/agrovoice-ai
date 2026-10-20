package com.agronomy.phytomonitor.voice

import org.junit.Assert.*
import org.junit.Test

class ListeningPermissionGateTest {
    @Test fun permissionResultPreservesContinuousAndDictationModes() {
        for (mode in ListeningMode.entries) {
            val gate = ListeningPermissionGate()
            val request = ListeningRequest(mode, "note/inspection-1")
            assertEquals(PermissionDecision.REQUEST, gate.begin(request, needsPermission = true))
            assertEquals(PermissionDecision.WAIT, gate.begin(request, needsPermission = true))
            assertEquals(request, gate.complete("note/inspection-1", listeningAllowed = true))
        }
    }

    @Test fun denialAllowsPhoneFallbackWithoutRepeatedPermissionDialogs() {
        val gate = ListeningPermissionGate()
        val request = ListeningRequest(ListeningMode.CONTINUOUS, "main")
        assertEquals(PermissionDecision.REQUEST, gate.begin(request, needsPermission = true))
        assertEquals(request, gate.complete("main", listeningAllowed = true))
        // Permission is still absent after denial; subsequent ASR sessions use phone fallback.
        repeat(3) {
            assertEquals(PermissionDecision.START, gate.begin(request, needsPermission = true))
        }
    }

    @Test fun cancelledOrStalePermissionResultNeverRestartsListening() {
        for (reason in listOf("cancel", "screen", "background")) {
            val gate = ListeningPermissionGate()
            val request = ListeningRequest(ListeningMode.DICTATION, "note/inspection-1")
            gate.begin(request, needsPermission = true)
            if (reason == "cancel") gate.cancel()
            val origin = if (reason == "screen") "note/inspection-2" else request.origin
            assertNull(gate.complete(origin, listeningAllowed = reason != "background"))
        }
    }

    @Test fun cancellationDoesNotOpenASecondDialogWhileFirstIsOutstanding() {
        val gate = ListeningPermissionGate()
        val request = ListeningRequest(ListeningMode.ONE_SHOT, "main")
        gate.begin(request, needsPermission = true)
        gate.cancel()
        assertEquals(PermissionDecision.WAIT, gate.begin(request, needsPermission = true))
        assertNull(gate.complete("main", listeningAllowed = true))
        assertEquals(PermissionDecision.START, gate.begin(request, needsPermission = true))
    }
}

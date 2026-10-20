package com.agronomy.phytomonitor.voice

internal enum class ListeningMode { ONE_SHOT, CONTINUOUS, DICTATION, NOTE_REVIEW, DIALOG, FOLLOW_UP }
internal data class ListeningRequest(val mode: ListeningMode, val origin: String)
internal enum class PermissionDecision { START, REQUEST, WAIT }

/** A denied optional Bluetooth permission uses the phone, without re-prompting each ASR session. */
internal class ListeningPermissionGate {
    private var answered = false
    private var inFlight = false
    private var pending: ListeningRequest? = null

    fun begin(request: ListeningRequest, needsPermission: Boolean): PermissionDecision {
        if (inFlight) return PermissionDecision.WAIT
        if (!needsPermission || answered) return PermissionDecision.START
        inFlight = true
        pending = request
        return PermissionDecision.REQUEST
    }

    fun complete(origin: String, listeningAllowed: Boolean): ListeningRequest? {
        answered = true
        inFlight = false
        val request = pending
        pending = null
        return request?.takeIf { listeningAllowed && it.origin == origin }
    }

    fun cancel() {
        pending = null
    }
}

package com.agronomy.phytomonitor.voice

/** Callbacks and public operations run on the main thread; synthesis runs in the backend. */
internal interface SpeechBackend {
    fun initialize(onReady: (String?) -> Unit)
    fun speak(text: String, volume: Float, onFinished: (Boolean) -> Unit)
    fun stop()
    fun close()
}

internal interface SpeechFocus {
    fun acquire(onLost: () -> Unit): Boolean
    fun release()
}

internal class SpeechOutput(
    private val local: SpeechBackend,
    private val system: SpeechBackend,
    private val focus: SpeechFocus,
    private val changed: () -> Unit = {},
) {
    var active = false
        private set
    var available = false
        private set
    var initialized = false
        private set
    var voiceName = ""
        private set
    var status = "Подготовка озвучивания…"
        private set
    var completion = "idle"
        private set
    private var localVoice: String? = null
    private var systemVoice: String? = null
    private var localInitialized = false
    private var systemInitialized = false
    private var generation = 0L
    private var closed = false

    init {
        local.initialize { name ->
            if (!closed) {
                localVoice = name
                localInitialized = true
                refresh()
            }
        }
        system.initialize { name ->
            if (!closed) {
                systemVoice = name
                systemInitialized = true
                refresh()
            }
        }
    }

    private fun refresh() {
        available = !closed && (localVoice != null || systemVoice != null)
        initialized = localInitialized && systemInitialized
        voiceName = localVoice ?: systemVoice.orEmpty()
        if (!active) {
            status = when {
                available -> "Озвучивание без интернета доступно: $voiceName"
                !initialized && !closed -> "Подготовка озвучивания…"
                else -> "Автономный русский голос недоступен. Результат доступен текстом."
            }
        }
        changed()
    }

    fun speak(text: String, volume: Float = 1f): Boolean {
        if (closed || !available || text.isBlank()) return false
        stop()
        if (!focus.acquire(::stop)) {
            status = "Звук занят другим приложением. Результат доступен текстом."
            completion = "error:focus"
            changed()
            return false
        }
        val request = ++generation
        active = true
        completion = "pending"
        status = "Читаю результат…"
        changed()
        start(request, text, volume.coerceIn(0f, 1f), useLocal = localVoice != null)
        return true
    }

    private fun start(request: Long, text: String, volume: Float, useLocal: Boolean) {
        val backend = if (useLocal) local else system
        backend.speak(text, volume) { success ->
            if (closed || request != generation) return@speak
            if (!success && useLocal) {
                localVoice = null
                if (systemVoice != null) {
                    voiceName = systemVoice.orEmpty()
                    start(request, text, volume, useLocal = false)
                    return@speak
                }
            }
            if (!success && !useLocal) systemVoice = null
            active = false
            completion = if (success) "done" else "error:playback"
            focus.release()
            refresh()
            if (!success) {
                status = "Не удалось озвучить результат. Он доступен текстом."
                changed()
            }
        }
    }

    fun stop() {
        generation++
        local.stop()
        system.stop()
        focus.release()
        active = false
        completion = "stopped"
        refresh()
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        local.close()
        system.close()
    }
}

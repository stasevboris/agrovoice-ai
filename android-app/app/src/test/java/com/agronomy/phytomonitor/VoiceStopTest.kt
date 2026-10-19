package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.voice.VoiceAction
import com.agronomy.phytomonitor.voice.VoiceHypotheses
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceStopTest {
    private fun hypothesis(text: String) = JSONObject().put("text", text)
        .put("result", JSONArray().put(JSONObject().put("conf", 0.2))).toString()

    @Test
    fun uncertainExplicitStopCanSilenceMicrophoneWithoutRelaxingOtherCommands() {
        assertEquals(VoiceAction.LISTEN_OFF, VoiceHypotheses.decode(hypothesis("стоп")).command?.action)
        assertNull(VoiceHypotheses.decode(hypothesis("сделай фото")).command)
        assertNull(VoiceHypotheses.decode(hypothesis("сохрани осмотр")).command)
        assertNull(VoiceHypotheses.decode(hypothesis("не выключай голос")).command)
        assertNull(VoiceHypotheses.decode(hypothesis("стоп"), "note").command)
    }
}

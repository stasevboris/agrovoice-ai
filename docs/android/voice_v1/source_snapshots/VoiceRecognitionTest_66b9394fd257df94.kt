package com.agronomy.phytomonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.voice.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class VoiceRecognitionTest {
    @Test fun recognizesRussianSamplesAndRejectsNegativeCommands() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"voice_validation")
        val cases=JSONArray(File(folder,"cases.json").readText().removePrefix("\uFEFF"))
        val evidence=JSONArray();var accepted=0;var expected=0;var falseActions=0
        Model(VoiceModelFiles.prepare(context).absolutePath).use {model ->
            for(index in 0 until cases.length()) {
                val case=cases.getJSONObject(index);val bytes=File(folder,case.getString("file")).readBytes()
                val pcm=pcm16k(bytes);val hypotheses=JSONArray();val commands=mutableListOf<VoiceCommand>()
                Recognizer(model,16000f,JSONArray(VoiceCommands.grammar).toString()).use {engine ->
                    engine.setWords(true)
                    fun record(text: String) {
                        val result=VoiceHypotheses.decode(text)
                        hypotheses.put(JSONObject().put("text",result.text).put("confidence",result.confidence)
                            .put("action",result.command?.action?.name ?: "").put("value",result.command?.value ?: ""))
                        result.command?.let {commands+=it}
                    }
                    pcm.asList().chunked(8000).forEach {block ->
                        val buffer=block.toByteArray()
                        if(engine.acceptWaveForm(buffer,buffer.size))record(engine.result)
                    }
                    record(engine.finalResult)
                }
                val action=case.getString("action");val value=case.getString("value")
                val correct=if(action.isEmpty())commands.isEmpty() else commands.size==1 && commands.single().action.name==action && commands.single().value==value
                if(action.isNotEmpty()){expected++;if(correct)accepted++}else if(!correct)falseActions++
                evidence.put(JSONObject().put("sample",case).put("hypotheses",hypotheses).put("passed",correct))
            }
        }
        File(folder,"recognition_results.json").writeText(JSONObject().put("accepted_commands",accepted).put("expected_commands",expected)
            .put("false_actions_on_negative_or_unrelated",falseActions).put("samples",evidence)
            .put("scope","Synthetic Russian WAVs decoded on actual Android CPU; not microphone acoustics or real field WER").toString(2))
        assertEquals("Negative/unrelated speech triggered an action",0,falseActions)
        assertTrue("Only $accepted/$expected synthetic commands accepted",accepted>=expected*0.8)
    }
    private fun pcm16k(bytes: ByteArray): ByteArray {
        require(String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WAVE")
        var offset=12;var format=false
        while(offset+8<=bytes.size) {
            val name=String(bytes,offset,4,Charsets.US_ASCII)
            val length=ByteBuffer.wrap(bytes,offset+4,4).order(ByteOrder.LITTLE_ENDIAN).int
            require(length>=0 && offset+8+length<=bytes.size)
            if(name=="fmt ") {
                val info=ByteBuffer.wrap(bytes,offset+8,length).order(ByteOrder.LITTLE_ENDIAN)
                require(info.short.toInt()==1 && info.short.toInt()==1 && info.int==16000)
                info.int;info.short;require(info.short.toInt()==16);format=true
            }
            if(name=="data"){require(format);return bytes.copyOfRange(offset+8,offset+8+length)}
            offset+=8+length+(length%2)
        }
        error("WAV has no PCM data")
    }
}

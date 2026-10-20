package com.agronomy.phytomonitor

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.ml.FieldDetector
import com.agronomy.phytomonitor.voice.VoiceCommands
import com.agronomy.phytomonitor.voice.VoiceHypotheses
import com.agronomy.phytomonitor.voice.VoiceModelFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed FLEURS val recordings: measures ASR, never executes parsed commands or opens a microphone. */
@RunWith(AndroidJUnit4::class)
class RecordedRussianAsrTest {
    @Test
    fun transcribeRecordedValidationSpeech() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification")) { "Isolated verification package only" }
        val folder = File(context.getExternalFilesDir(null), "fleurs_validation_v1")
        val manifest = JSONObject(File(folder, "cases.json").readText())
        require(manifest.getString("split") == "val" && manifest.getString("locale") == "ru_ru")
        val cases = manifest.getJSONArray("cases")
        require(cases.length() == 12) { "Expected fixed twelve-recording sample" }
        val grammar = JSONArray(VoiceCommands.mainGrammar(emptyList()))
        val voiceManifest = JSONObject(context.assets.open("voice/manifest.json").bufferedReader().use { it.readText() })
        val evidence = JSONArray()
        val report = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("package", context.packageName)
            .put("voice_archive_sha256", voiceManifest.getString("archive_sha256"))
            .put("main_grammar", grammar)
            .put("created_epoch_ms", System.currentTimeMillis())
            .put("complete", false)
            .put("samples", evidence)
            .put("scope", "Recorded FLEURS Russian read speech; no microphone, field speech or command labels")
            .put("decoder", "VoiceHypotheses.decode(screen=main); decoded commands recorded, never executed")
        val output = File(folder, "recognition_results.json")
        Model(VoiceModelFiles.prepare(context).absolutePath).use { model ->
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                require(case.getString("split") == "val")
                val name = case.getString("file")
                require(name.matches(Regex("sample_[0-9]{2}\\.wav")))
                val file = File(folder, name)
                val digest = FieldDetector.sha256(file)
                check(digest == case.getString("pcm_sha256")) { "PCM recording hash mismatch" }
                val pcm = pcm16k(file.readBytes())
                require(pcm.size / 2 == case.getInt("num_samples"))
                for (mode in listOf("main_grammar", "dictation")) {
                    val segments = JSONArray()
                    val transcript = mutableListOf<String>()
                    val started = SystemClock.elapsedRealtimeNanos()
                    val recognizer = if (mode == "main_grammar") {
                        Recognizer(model, 16000f, grammar.toString())
                    } else {
                        Recognizer(model, 16000f)
                    }
                    recognizer.use { engine ->
                        engine.setWords(true)
                        fun record(raw: String) {
                            val decoded = VoiceHypotheses.decode(raw, "main")
                            segments.put(JSONObject()
                                .put("raw_vosk", JSONObject(raw))
                                .put("text", decoded.text)
                                .put("confidence", decoded.confidence)
                                .put("action", decoded.command?.action?.name ?: "")
                                .put("value", decoded.command?.value ?: ""))
                            if (decoded.text.isNotBlank()) transcript += decoded.text
                        }
                        var offset = 0
                        while (offset < pcm.size) {
                            val end = minOf(offset + 8000, pcm.size)
                            val block = pcm.copyOfRange(offset, end)
                            if (engine.acceptWaveForm(block, block.size)) record(engine.result)
                            offset = end
                        }
                        record(engine.finalResult)
                    }
                    evidence.put(JSONObject()
                        .put("file", name)
                        .put("pcm_sha256", digest)
                        .put("mode", mode)
                        .put("segments", segments)
                        .put("transcript", transcript.joinToString(" "))
                        .put("recognition_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1e6))
                    output.writeText(report.toString(2))
                }
            }
        }
        report.put("complete", true)
        output.writeText(report.toString(2))
    }

    private fun pcm16k(bytes: ByteArray): ByteArray {
        require(bytes.size >= 12)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var offset = 12
        var validFormat = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(length >= 0 && length <= bytes.size - offset - 8)
            if (name == "fmt ") {
                require(length >= 16)
                val info = ByteBuffer.wrap(bytes, offset + 8, length).order(ByteOrder.LITTLE_ENDIAN)
                require(info.short.toInt() == 1 && info.short.toInt() == 1 && info.int == 16000)
                require(info.int == 32000 && info.short.toInt() == 2 && info.short.toInt() == 16)
                validFormat = true
            }
            if (name == "data") {
                require(validFormat && length % 2 == 0)
                return bytes.copyOfRange(offset + 8, offset + 8 + length)
            }
            offset += 8 + length + length % 2
        }
        error("WAV has no PCM data")
    }
}

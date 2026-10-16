package com.agronomy.phytomonitor.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agronomy.phytomonitor.ml.FieldDetector
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

object VoiceModelFiles {
    fun prepare(context: Context): File {
        val manifest = JSONObject(context.assets.open("voice/manifest.json").bufferedReader().use {it.readText()})
        val folder = File(context.filesDir,"voice-ru").apply {mkdirs()}
        val marker = File(folder,".complete")
        val digest = manifest.getString("archive_sha256")
        if(marker.isFile && marker.readText() == digest && File(folder,"am/final.mdl").isFile()) return folder
        val files = manifest.getJSONArray("files")
        for(i in 0 until files.length()) {
            val item = files.getJSONObject(i); val relative = item.getString("path")
            val target = File(folder,relative).canonicalFile
            require(target.toPath().startsWith(folder.canonicalFile.toPath()))
            target.parentFile!!.mkdirs()
            val temporary = File(target.parentFile,target.name+".part")
            context.assets.open("voice/ru/$relative").use {source -> temporary.outputStream().use {source.copyTo(it)}}
            require(FieldDetector.sha256(temporary) == item.getString("sha256")) {"Не удалось подготовить голосовую модель"}
            require(!target.exists() || target.delete())
            require(temporary.renameTo(target))
        }
        marker.writeText(digest)
        return folder
    }
}

data class RecognizedVoice(val text: String, val confidence: Double, val command: VoiceCommand?)
object VoiceHypotheses {
    fun decode(hypothesis: String): RecognizedVoice {
        val data=JSONObject(hypothesis);val text=data.optString("text").trim()
        val words=data.optJSONArray("result")
        val confidence=if(words!=null && words.length()>0)(0 until words.length()).map {words.getJSONObject(it).optDouble("conf",0.0)}.average() else 0.0
        val command=VoiceCommands.parse(text).takeIf {confidence.isFinite() && confidence>=0.65 && !text.contains("[unk]")}
        return RecognizedVoice(text,confidence,command)
    }
}

/** One phrase per session. The gallery may explicitly request a bounded follow-up session. */
class OfflineVoiceController(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var token = 0
    @Volatile private var closed = false
    private var model: Model? = null // Native resources owned by worker.
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    var active by mutableStateOf(false); private set
    var status by mutableStateOf("Голос работает без интернета"); private set
    var heard by mutableStateOf(""); private set
    var onCommand: (VoiceCommand)->Unit = {}

    fun start(grammar: List<String>? = VoiceCommands.grammar) {
        if(active || closed)return
        val session = ++token
        active=true; heard=""; status="Подготавливаю распознавание…"
        worker.execute {
            try {
                if(model == null) model=Model(VoiceModelFiles.prepare(context).absolutePath)
                if(session!=token || closed)return@execute
                val engine=if(grammar==null)Recognizer(model,16000f) else Recognizer(model,16000f,JSONArray(grammar).toString())
                recognizer=engine;engine.setWords(true)
                val microphone=SpeechService(engine,16000f);service=microphone
                val listener=object: RecognitionListener {
                    override fun onPartialResult(hypothesis: String) {
                        if(session!=token || closed)return
                        heard=JSONObject(hypothesis).optString("partial").replace("[unk]","").trim()
                    }
                    override fun onResult(hypothesis: String) { finishPhrase(session,hypothesis) }
                    override fun onFinalResult(hypothesis: String) { finishPhrase(session,hypothesis) }
                    override fun onError(exception: Exception) { finish(session,"Микрофон недоступен. Проверь разрешение и повтори.") }
                    override fun onTimeout() { finish(session,"Не услышал команду. Нажми кнопку и повтори.") }
                }
                if(session!=token || closed){releaseSession();return@execute}
                main.post {if(session==token && !closed)status="Слушаю. Скажи одну команду…"}
                check(microphone.startListening(listener,12000))
            } catch(error: Exception) {
                releaseSession()
                main.post {if(session==token && !closed){active=false;status=error.message ?: "Голосовая команда недоступна"}}
            }
        }
    }
    private fun finishPhrase(session: Int, hypothesis: String) {
        if(session!=token || closed)return
        val recognized=VoiceHypotheses.decode(hypothesis);val text=recognized.text
        if(text.isEmpty())return
        val command=recognized.command
        heard=text.replace("[unk]","").trim()
        finish(session,command?.label ?: "Команда не распознана. Попробуй фразу из списка.",command)
    }
    private fun finish(session: Int, message: String, command: VoiceCommand? = null) {
        if(session!=token || closed)return
        val finished=++token // Drop any queued duplicate result from this recording.
        worker.execute {
            releaseSession()
            main.post {if(finished==token && !closed){active=false;status=message;if(command!=null)onCommand(command)}}
        }
    }
    private fun releaseSession() {
        service?.cancel();service?.shutdown();service=null
        recognizer?.close();recognizer=null
    }
    fun stop() {
        if(closed)return
        ++token;active=false;status="Голос работает без интернета"
        worker.execute {releaseSession()}
    }
    fun permissionDenied(){status="Разреши доступ к микрофону для голосовых команд. Фото можно проверять кнопками."}
    fun close() {
        if(closed)return
        closed=true;++token;active=false
        worker.execute {releaseSession();model?.close();model=null}
        worker.shutdown()
    }
}

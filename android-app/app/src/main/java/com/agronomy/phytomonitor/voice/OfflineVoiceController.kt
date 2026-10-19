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
    fun decode(hypothesis: String, screen: String="main", plots: Collection<String> = emptyList()): RecognizedVoice {
        val data=JSONObject(hypothesis);val text=data.optString("text").trim()
        val words=data.optJSONArray("result")
        val confidence=if(words!=null && words.length()>0)(0 until words.length()).map {words.getJSONObject(it).optDouble("conf",0.0)}.average() else 0.0
        val command=VoiceCommands.parseForScreen(text,screen,plots).takeIf {confidence.isFinite() && confidence>=0.65 && !text.contains("[unk]")}
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
    var onFailure: ()->Unit = {}
    var onDictation: (String)->Unit = {}
    var onDictationFinished: ()->Unit = {}
    /** Names of the existing plots, so «участок теплица два» can be understood. */
    var plotNames: List<String> = emptyList()
    private var continuous=false
    private var screen="main"

    fun start(grammar: List<String>? = VoiceCommands.grammar, continuous: Boolean=false, screen: String="main") {
        if(active || closed)return
        this.continuous=continuous
        this.screen=screen
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
                        val partial=JSONObject(hypothesis).optString("partial").replace("[unk]","").trim()
                        main.post {if(session==token && !closed)heard=partial}
                    }
                    override fun onResult(hypothesis: String) { finishPhrase(session,hypothesis) }
                    override fun onFinalResult(hypothesis: String) { if(!continuous)finishPhrase(session,hypothesis) }
                    override fun onError(exception: Exception) { finish(session,"Микрофон недоступен. Проверь разрешение и повтори.",failed=true) }
                    override fun onTimeout() { finish(session,"Не услышал команду. Нажми кнопку и повтори.") }
                }
                if(session!=token || closed){releaseSession();return@execute}
                main.post {if(session==token && !closed)status=if(continuous)"Слушаю постоянно…" else "Слушаю. Скажи одну команду…"}
                check(if(continuous)microphone.startListening(listener) else microphone.startListening(listener,12000))
            } catch(error: Exception) {
                releaseSession()
                main.post {if(session==token && !closed){active=false;status=error.message ?: "Голосовая команда недоступна";onFailure()}}
            }
        }
    }
    private fun finishPhrase(session: Int, hypothesis: String) {
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post {finishPhrase(session,hypothesis)};return}
        if(session!=token || closed)return
        val recognized=VoiceHypotheses.decode(hypothesis,screen,plotNames);val text=recognized.text
        if(text.isEmpty())return
        if(screen=="note") {
            val normalized=text.lowercase().replace('ё','е').trim()
            if(normalized in setOf("закончить заметку","сохранить заметку","стоп","отмена")){
                finish(session,"Диктовка остановлена. Проверь текст заметки.",onEnded=onDictationFinished)
            } else {heard=text;onDictation(text)}
            return
        }
        val command=recognized.command
        heard=text.replace("[unk]","").trim()
        if(continuous) {
            status=if(command==null)"Не понял команду. Продолжаю слушать…" else "Слушаю постоянно…"
            if(command!=null)onCommand(command)
        } else finish(session,command?.label ?: "Команда не распознана. Попробуй фразу из списка.",command)
    }
    /** Also exercised by the on-device PCM test bench; microphone listeners use the same final-result path. */
    internal fun acceptFinalHypothesis(hypothesis: String) {if(active)finishPhrase(token,hypothesis)}
    private fun finish(session: Int, message: String, command: VoiceCommand? = null, failed: Boolean=false,onEnded: ()->Unit={}) {
        if(session!=token || closed)return
        val finished=++token // Drop any queued duplicate result from this recording.
        worker.execute {
            releaseSession()
            main.post {if(finished==token && !closed){active=false;status=message;if(failed)onFailure();if(command!=null)onCommand(command);onEnded()}}
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

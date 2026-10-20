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
import org.vosk.android.SpeechStreamService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

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

data class RecognizedVoice(val text: String, val confidence: Double, val command: VoiceCommand?) {
    /** Vosk word confidences are high enough and no word was unknown to the model. */
    val confident: Boolean get() = confidence.isFinite() && confidence>=0.65 && !text.contains("[unk]")
}
object VoiceHypotheses {
    fun decode(hypothesis: String, screen: String="main", plots: Collection<String> = emptyList()): RecognizedVoice {
        val data=JSONObject(hypothesis);val text=data.optString("text").trim()
        val words=data.optJSONArray("result")
        val confidence=if(words!=null && words.length()>0)(0 until words.length()).map {words.getJSONObject(it).optDouble("conf",0.0)}.average() else 0.0
        // An explicit microphone stop is safe even when the acoustic score is low. Other actions keep
        // the normal threshold; uncertain speech must not capture, save or select anything.
        val parsed = VoiceCommands.parseForScreen(text, screen, plots)
        val stop = parsed?.action == VoiceAction.LISTEN_OFF && text.equals("стоп", ignoreCase = true)
        val command = parsed.takeIf { confidence.isFinite() && (confidence >= 0.65 || stop) && !text.contains("[unk]") }
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
    private var service: SpeechStreamService? = null
    private var audioSession: InputRouteSession? = null
    private var phraseTimeout: Runnable? = null
    private var waitingForStoppedAction by mutableStateOf(false)
    private var stoppedActionGeneration = 0L
    var active by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    val readyToStart: Boolean get() = !active && !preparing && !waitingForStoppedAction && !closed
    val hasPendingStoppedAction: Boolean get() = waitingForStoppedAction
    var status by mutableStateOf("Голос работает без интернета"); private set
    var heard by mutableStateOf(""); private set
    var inputRouteStatus by mutableStateOf(""); private set
    var onCommand: (VoiceCommand)->Unit = {}
    /** Several commands from one free phrase («прочитай результат и сохрани осмотр»). */
    var onCommands: (List<VoiceCommand>)->Unit = {}
    /** Experimental: free Russian phrases are understood by FreeSpeech when no fixed phrase matches. */
    var freeSpeech = false
    var onFailure: ()->Unit = {}
    var onDictation: (String)->Unit = {}
    var onDictationFinished: ()->Unit = {}
    /** Names of the existing plots, so «участок теплица два» can be understood. */
    var plotNames: List<String> = emptyList()
    private var continuous=false
    private var screen="main"

    fun start(grammar: List<String>? = VoiceCommands.grammar, continuous: Boolean=false, screen: String="main") {
        if(!readyToStart)return
        this.continuous=continuous
        this.screen=screen
        val session = ++token
        preparing=true; active=false; heard=""; inputRouteStatus=""; status="Подготавливаю распознавание и микрофон…"
        worker.execute {
            try {
                if(model == null) model=Model(VoiceModelFiles.prepare(context).absolutePath)
                if(session!=token || closed)return@execute
                val engine=if(grammar==null)Recognizer(model,16000f) else Recognizer(model,16000f,JSONArray(grammar).toString())
                recognizer=engine;engine.setWords(true)
                val route = InputRouteSession(AudioInputRoute(context) { reason -> finish(session, reason, failed = true) })
                audioSession = route
                val input = route.open { session != token || closed }
                val microphone = SpeechStreamService(engine, input.stream, 16000f)
                service = microphone
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
                check(microphone.start(listener))
                main.post {
                    if (session == token && !closed) {
                        preparing = false
                        active = true
                        inputRouteStatus = when (input.device.kind) {
                            InputKind.PHONE -> "Вход: микрофон телефона; гарнитура не выбрана или недоступна."
                            InputKind.WIRED -> "Вход: проводная гарнитура."
                            InputKind.USB -> "Вход: USB-микрофон."
                            InputKind.BLUETOOTH -> "Вход: Bluetooth-микрофон."
                        } + " Маршрут Android подтверждён; слышимость не проверена."
                        status = listeningStatus()
                        // Vosk 0.3.75's stream timeout counts bytes as samples. Keep the existing
                        // 12-second session with our cancellable wall-clock timer instead.
                        if (!continuous) {
                            val timeout = Runnable { finish(session, "Не услышал команду. Нажми кнопку и повтори.") }
                            phraseTimeout = timeout
                            main.postDelayed(timeout, 12000)
                        }
                    }
                }
            } catch(error: Exception) {
                val cleanup = releaseSession()
                main.post {if(session==token && !closed){active=false;preparing=false;status=cleanup ?: error.message ?: "Голосовая команда недоступна";onFailure()}}
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
        val understood=if(recognized.command==null && freeSpeech && recognized.confident)FreeSpeech.understand(text,screen,plotNames) else null
        val plan=(understood as? Understanding.Commands)?.commands.orEmpty()
        val command=recognized.command ?: plan.singleOrNull()
        val several=plan.takeIf {it.size>1}
        val refusal=(understood as? Understanding.Refusal)?.text
        heard=text.replace("[unk]","").trim()
        val failure=refusal ?: if(freeSpeech)"Не понял. Скажи по-другому или фразу из списка." else "Команда не распознана. Попробуй фразу из списка."
        if(continuous) {
            status=if(command==null && several==null)failure else listeningStatus()
            if(command!=null)onCommand(command)
            if(several!=null)onCommands(several)
        } else finish(session,command?.label ?: several?.joinToString(", ") {it.label} ?: failure,command,several=several)
    }
    /** Also exercised by the on-device PCM test bench; microphone listeners use the same final-result path. */
    internal fun acceptFinalHypothesis(hypothesis: String) {if(active)finishPhrase(token,hypothesis)}
    private fun finish(session: Int, message: String, command: VoiceCommand? = null, failed: Boolean=false,onEnded: ()->Unit={},several: List<VoiceCommand>?=null) {
        if(session!=token || closed)return
        val finished=++token // Drop any queued duplicate result from this recording.
        clearPhraseTimeout()
        worker.execute {
            val cleanup = releaseSession()
            main.post {
                if (finished == token && !closed) {
                    active = false
                    preparing = false
                    status = cleanup ?: message
                    if (failed || cleanup != null) onFailure()
                    if (cleanup == null) {
                        if (command != null) onCommand(command)
                        if (several != null) onCommands(several)
                        onEnded()
                    }
                }
            }
        }
    }
    private fun listeningStatus(): String =
        if (continuous) "Слушаю постоянно…" else "Слушаю. Скажи одну команду…"

    private fun clearPhraseTimeout() {
        phraseTimeout?.let(main::removeCallbacks)
        phraseTimeout = null
    }

    private fun releaseSession(): String? {
        val route = audioSession
        val microphone = service
        val engine = recognizer
        audioSession = null
        service = null
        recognizer = null
        val errors = mutableListOf<Exception>()
        fun release(action: () -> Unit) {
            try { action() } catch (error: Exception) { errors += error }
        }
        release { route?.stopCapture() }
        release { microphone?.stop() }
        release { route?.close() }
        release { engine?.close() }
        return if (errors.isEmpty()) null else "Не удалось полностью освободить микрофон. Повтори запуск приложения."
    }
    fun stop(onStopped: (() -> Unit)? = null): Future<*>? {
        if(closed)return null
        val stopped = ++token
        val actionGeneration = ++stoppedActionGeneration
        waitingForStoppedAction = onStopped != null
        clearPhraseTimeout()
        active=false;preparing=false;status="Голос работает без интернета";inputRouteStatus=""
        return worker.submit {
            val cleanup = releaseSession()
            main.post {
                if (stopped == token && !closed) {
                    waitingForStoppedAction = false
                    if (cleanup != null) {
                        status = cleanup
                        onFailure()
                    } else if (actionGeneration == stoppedActionGeneration) onStopped?.invoke()
                }
            }
        }
    }
    /** Cancel a queued speech start without stopping an independently requested listening session. */
    fun cancelStoppedAction() {
        stoppedActionGeneration++
        waitingForStoppedAction = false
    }
    fun permissionDenied(){status="Разреши доступ к микрофону для голосовых команд. Фото можно проверять кнопками."}
    /** The executor's actual outcome replaces the recognized command label on refusals/skips. */
    fun feedback(text: String) { status = text }
    fun close() {
        if(closed)return
        closed=true;++token;active=false;preparing=false;waitingForStoppedAction=false
        clearPhraseTimeout()
        worker.execute {releaseSession();model?.close();model=null}
        worker.shutdown()
    }
}

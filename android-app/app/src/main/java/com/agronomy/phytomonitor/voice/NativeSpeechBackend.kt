package com.agronomy.phytomonitor.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal data class SpeechPlayback(
    val generatedSamples: Long,
    val playedSamples: Long,
    val sampleRate: Int,
    val peak: Float,
)

/** The worker exclusively owns JNI. stop() silences AudioTrack immediately, then cancels synthesis. */
internal class NativeSpeechBackend(private val context: Context) : SpeechBackend {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val audioLock = Any()
    private var track: AudioTrack? = null
    private var engine: OfflineTts? = null
    @Volatile private var closed = false
    @Volatile var lastPlayback: SpeechPlayback? = null
        private set

    override fun initialize(onReady: (String?) -> Unit) {
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            var prepared = started
            val name = runCatching {
                Class.forName("com.k2fsa.sherpa.onnx.OfflineTts", true, javaClass.classLoader)
                val model = prepareModel()
                prepared = SystemClock.elapsedRealtime()
                if (closed) return@runCatching null
                engine = OfflineTts(config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(model, "ru_RU-denis-medium.onnx").path,
                            tokens = File(model, "tokens.txt").path,
                            dataDir = File(model, "espeak-ng-data").path,
                        ),
                        numThreads = 2, provider = "cpu", debug = false,
                    ),
                    maxNumSentences = 1,
                ))
                "Denis (встроенный)"
            }.onFailure { Log.w("AgroVoiceTts", "Built-in voice unavailable", it) }.getOrNull()
            Log.i("AgroVoiceTts", "Voice initialization took ${SystemClock.elapsedRealtime() - started} ms " +
                "(files ${prepared - started} ms)")
            main.post { if (!closed) onReady(name) }
        }
    }

    /**
     * Copies and hashes the voice once. Like the detector models, later starts trust a marker with the
     * manifest and file sizes: hashing 36 MB in 359 files took ~7 s on every Redmi Note 7 start.
     */
    private fun prepareModel(): File {
        val text = context.assets.open("tts/manifest.json").bufferedReader().use { it.readText() }
        val manifest = JSONObject(text)
        val model = manifest.getString("model")
        val root = File(context.filesDir, "tts/$model")
        val files = manifest.getJSONArray("files")
        val marker = File(root, ".verified")
        fun stamp(): String = text.hashCode().toString() + ":" + (0 until files.length()).sumOf { index ->
            File(root, files.getJSONObject(index).getString("path")).length()
        }
        if (marker.isFile && marker.readText() == stamp()) return root
        marker.delete()
        for (index in 0 until files.length()) {
            check(!closed) { "Voice initialization cancelled" }
            val item = files.getJSONObject(index)
            val path = item.getString("path")
            val expected = item.getString("sha256")
            val target = File(root, path)
            if (target.isFile && digest(target) == expected) continue
            target.parentFile!!.mkdirs()
            val temporary = File(target.path + ".part")
            context.assets.open("tts/$model/$path").use { source ->
                temporary.outputStream().use { source.copyTo(it) }
            }
            check(digest(temporary) == expected) { "Corrupt built-in voice: $path" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        marker.writeText(stamp())
        return root
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    override fun speak(text: String, volume: Float, onFinished: (Boolean) -> Unit) {
        stop()
        if (closed) return
        val request = generation.get()
        lastPlayback = null
        worker.execute {
            if (cancelled(request)) return@execute
            val success = runCatching { play(text, volume, request) }
                .onFailure { Log.w("AgroVoiceTts", "Built-in playback failed", it) }
                .getOrDefault(false)
            main.post { if (!cancelled(request)) onFinished(success) }
        }
    }

    private fun cancelled(request: Long): Boolean = closed || generation.get() != request

    private fun play(text: String, volume: Float, request: Long): Boolean {
        val tts = engine ?: return false
        val rate = tts.sampleRate()
        val bufferBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(bufferBytes > 0) { "Unsupported speech audio format" }
        val player = AudioTrack.Builder()
            .setAudioAttributes(speechAudioAttributes())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(bufferBytes, rate / 5 * 4))
            .build()
        var generated = 0L
        var peak = 0f
        var failed = false
        try {
            synchronized(audioLock) {
                if (cancelled(request)) return false
                check(player.state == AudioTrack.STATE_INITIALIZED)
                track = player
                player.setVolume(volume)
                player.play()
            }
            tts.generateWithCallback(text, sid = 0, speed = 1f, callback = TypedPcmCallback { samples ->
                if (cancelled(request)) return@TypedPcmCallback 0
                // Never propagate a Kotlin exception through the JNI callback boundary.
                try {
                    for (sample in samples) {
                        check(sample.isFinite()) { "Non-finite synthesized audio" }
                        peak = maxOf(peak, abs(sample))
                    }
                    var offset = 0
                    var stalledAt = SystemClock.elapsedRealtime()
                    while (offset < samples.size && !cancelled(request)) {
                        val count = player.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(count >= 0) { "AudioTrack write failed: $count" }
                        if (count > 0) {
                            offset += count
                            stalledAt = SystemClock.elapsedRealtime()
                        } else {
                            check(SystemClock.elapsedRealtime() - stalledAt < 5000) { "Audio output stalled" }
                            Thread.sleep(10)
                        }
                    }
                    generated += offset
                    lastPlayback = SpeechPlayback(generated, played(player), rate, peak)
                    if (cancelled(request)) 0 else 1
                } catch (error: Exception) {
                    failed = true
                    Log.w("AgroVoiceTts", "Audio chunk failed", error)
                    0
                }
            })
            if (failed || cancelled(request) || generated == 0L) return false
            // Keep active/focus until the last sample has actually left the playback queue.
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (played(player) < generated && !cancelled(request)) {
                check(SystemClock.elapsedRealtime() < deadline) { "Audio drain timed out" }
                Thread.sleep(10)
            }
            if (cancelled(request)) return false
            lastPlayback = SpeechPlayback(generated, played(player), rate, peak)
            return true
        } finally {
            synchronized(audioLock) {
                if (track === player) track = null
                runCatching { player.pause() }
                runCatching { player.flush() }
                player.release()
            }
        }
    }

    private fun played(player: AudioTrack): Long = player.playbackHeadPosition.toLong() and 0xffffffffL

    override fun stop() {
        generation.incrementAndGet()
        synchronized(audioLock) {
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        worker.execute {
            engine?.release()
            engine = null
        }
        worker.shutdown()
    }
}

/** JNI resolves invoke(float[]):Integer; indy/erased lambdas can abort ART. */
@Keep
internal class TypedPcmCallback(private val block: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = block(samples)
}

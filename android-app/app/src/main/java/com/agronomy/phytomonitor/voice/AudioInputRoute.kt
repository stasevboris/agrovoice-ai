package com.agronomy.phytomonitor.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Android session resources only. PCM is consumed in memory by Vosk and never logged or saved. */
@Suppress("DEPRECATION")
class AudioInputRoute(
    context: Context,
    private val onInterrupted: (String) -> Unit = {}
) : InputRoutingBackend {
    private val context = context.applicationContext
    private val audio = requireNotNull(this.context.getSystemService(AudioManager::class.java))
    private val main = Handler(Looper.getMainLooper())
    private val interrupted = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var monitoredInput: InputDevice? = null
    private var pcm: PcmCaptureStream? = null
    private var leased = false
    private var focusGranted = false
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var modeChanged = false
    private var communicationRequested = false
    private var scoRequested = false
    private var scoReceiver: BroadcastReceiver? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    override val bluetoothAllowed: Boolean
        get() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun acquire(cancelled: () -> Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Разреши доступ к микрофону. Фото можно проверять кнопками.")
        }
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (!sessionLease.tryAcquire(40, TimeUnit.MILLISECONDS)) {
            checkCancellation(cancelled)
            if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Предыдущий сеанс микрофона ещё завершается. Повтори команду.")
        }
        leased = true
        checkCancellation(cancelled)
        previousMode = audio.mode
        if (!InputRoutePolicy.canAcquireMode(previousMode)) throw IOException("Аудиосвязь занята другим приложением. Повтори после её завершения.")
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) interrupt("Слушание остановлено: аудио требуется другому приложению.")
            }, main).build()
        focusRequest = request
        focusGranted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusGranted) throw IOException("Не удалось получить доступ к аудио. Повтори команду; кнопки остаются доступны.")
    }

    override fun devices(): List<InputDevice> {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull(::describe).toMutableList()
        if (Build.VERSION.SDK_INT >= 31 && inputs.none { it.kind == InputKind.BLUETOOTH }) {
            runCatching { audio.availableCommunicationDevices.firstOrNull { isBluetooth(it.type) } }
                .getOrNull()?.let { inputs += InputDevice(it.id, InputKind.BLUETOOTH) }
        }
        return inputs
    }

    override fun prepare(device: InputDevice, cancelled: () -> Boolean): Boolean {
        if (device.kind != InputKind.BLUETOOTH) return true
        if (!bluetoothAllowed) return false
        checkCancellation(cancelled)
        if (Build.VERSION.SDK_INT < 31 && !audio.isBluetoothScoAvailableOffCall) return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        modeChanged = true
        if (Build.VERSION.SDK_INT >= 31) {
            val target = audio.availableCommunicationDevices.firstOrNull { isBluetooth(it.type) } ?: return false
            communicationRequested = audio.setCommunicationDevice(target)
            if (!communicationRequested) return false
            val deadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                checkCancellation(cancelled)
                if (audio.communicationDevice?.id == target.id) return true
                Thread.sleep(40)
            }
            return false
        }
        val connected = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown()
                if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && monitoredInput?.kind == InputKind.BLUETOOTH) {
                    interrupt("Гарнитура отключилась. Слушание остановлено; нажми микрофон для нового сеанса.")
                }
            }
        }
        val sticky = ContextCompat.registerReceiver(context, receiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)
        scoReceiver = receiver
        if (sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown()
        scoRequested = true
        audio.startBluetoothSco()
        val deadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            checkCancellation(cancelled)
            if (connected.await(40, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    override fun startRecorder(device: InputDevice): InputStream {
        // Route negotiation can outlive the permission granted at acquire().
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Разреши доступ к микрофону. Фото можно проверять кнопками.")
        }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("Android не поддерживает требуемый формат микрофона")
        val recording = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 6400)).build()
        recorder = recording
        val stream = PcmCaptureStream(object : PcmCapture {
            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                recording.read(buffer, offset, length, AudioRecord.READ_NON_BLOCKING)
            override fun stop() {
                if (recording.recordingState == AudioRecord.RECORDSTATE_RECORDING) recording.stop()
            }
            override fun release() = recording.release()
        })
        pcm = stream
        if (recording.state != AudioRecord.STATE_INITIALIZED) throw IOException("Микрофон не готов")
        val preferred = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            val candidate = describe(it)
            candidate != null && InputRoutePolicy.confirmed(device, candidate)
        } ?: throw IOException("Выбранный микрофон больше не подключён")
        if (!recording.setPreferredDevice(preferred)) throw IOException("Android отклонил выбранный микрофон")
        recording.addOnRoutingChangedListener(routingListener, main)
        if (Build.VERSION.SDK_INT >= 29) {
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    if (monitoredInput != null && configs.orEmpty().any { it.isClientSilenced }) {
                        interrupt("Android отключил звук микрофона. Заверши другую запись и повтори команду.")
                    }
                }
            }
            recording.registerAudioRecordingCallback(ContextCompat.getMainExecutor(context), callback)
            recordingCallback = callback
        }
        recording.startRecording()
        if (recording.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Не удалось начать слушание")
        return stream
    }

    override fun actualInput(): InputDevice? = recorder?.routedDevice?.let(::describe)

    override fun armMonitoring(device: InputDevice) {
        monitoredInput = device
        if (Build.VERSION.SDK_INT >= 29 && recorder?.activeRecordingConfiguration?.isClientSilenced == true) {
            interrupt("Android отключил звук микрофона. Заверши другую запись и повтори команду.")
        }
    }

    private val routingListener = AudioRouting.OnRoutingChangedListener {
        val expected = monitoredInput
        if (expected != null && !InputRoutePolicy.confirmed(expected, actualInput())) {
            interrupt("Аудиовход изменился. Слушание остановлено; нажми микрофон для нового сеанса.")
        }
    }

    private fun interrupt(message: String) {
        if (closed || !interrupted.compareAndSet(false, true)) return
        main.post { if (!closed) onInterrupted(message) }
    }

    override fun stopCapture() {
        monitoredInput = null
        pcm?.stopCapture()
    }

    override fun resetRoute() {
        monitoredInput = null
        val errors = mutableListOf<Exception>()
        val recording = recorder
        recorder = null
        recording?.let { tryCleanup(errors) { it.removeOnRoutingChangedListener(routingListener) } }
        if (Build.VERSION.SDK_INT >= 29) {
            recordingCallback?.let { callback -> tryCleanup(errors) { recording?.unregisterAudioRecordingCallback(callback) } }
        }
        recordingCallback = null
        val stream = pcm
        pcm = null
        tryCleanup(errors) { stream?.close() }
        scoReceiver?.let { receiver -> tryCleanup(errors) { context.unregisterReceiver(receiver) } }
        scoReceiver = null
        if (Build.VERSION.SDK_INT >= 31 && communicationRequested) {
            tryCleanup(errors) { audio.clearCommunicationDevice() }
            communicationRequested = false
        }
        if (scoRequested) {
            tryCleanup(errors) { audio.stopBluetoothSco() }
            scoRequested = false
        }
        if (modeChanged) {
            // setMode is owned by this application's route token; remove our request, not another app's call.
            tryCleanup(errors) { audio.mode = previousMode }
            modeChanged = false
        }
        if (errors.isNotEmpty()) throw IOException("Не удалось полностью освободить аудиомаршрут", errors.first())
    }

    override fun close() {
        if (closed) return
        closed = true
        val errors = mutableListOf<Exception>()
        tryCleanup(errors) { resetRoute() }
        if (focusGranted) focusRequest?.let { request -> tryCleanup(errors) { audio.abandonAudioFocusRequest(request) } }
        focusGranted = false
        focusRequest = null
        if (leased) {
            leased = false
            sessionLease.release()
        }
        if (errors.isNotEmpty()) throw IOException("Не удалось полностью освободить аудио. Повтори запуск приложения.", errors.first())
    }

    private fun checkCancellation(cancelled: () -> Boolean) {
        if (closed || cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Listening cancelled")
    }

    private fun tryCleanup(errors: MutableList<Exception>, action: () -> Unit) {
        try { action() } catch (error: Exception) { errors += error }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CONNECTION_TIMEOUT_MS = 4000L
        private val sessionLease = Semaphore(1)

        fun needsBluetoothPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) return false
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            return runCatching {
                audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any { isBluetooth(it.type) } ||
                    audio.availableCommunicationDevices.any { isBluetooth(it.type) }
            }.getOrDefault(false)
        }

        private fun isBluetooth(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET

        private fun describe(device: AudioDeviceInfo): InputDevice? {
            val kind = when {
                device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputKind.PHONE
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> InputKind.WIRED
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> InputKind.USB
                isBluetooth(device.type) -> InputKind.BLUETOOTH
                else -> return null
            }
            return InputDevice(device.id, kind)
        }
    }
}

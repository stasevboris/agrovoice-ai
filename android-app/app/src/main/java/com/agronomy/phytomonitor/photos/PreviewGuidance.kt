package com.agronomy.phytomonitor.photos

import com.agronomy.phytomonitor.PhotoVerdict
import com.agronomy.phytomonitor.analysePhoto
import com.agronomy.phytomonitor.qualitySampleSize
import java.nio.ByteBuffer

/** Bounded luminance sample; respects camera crop, buffer offset and padded row/pixel strides. */
fun previewVerdict(buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
    left: Int, top: Int, width: Int, height: Int): PhotoVerdict {
    require(rowStride > 0 && pixelStride > 0 && left >= 0 && top >= 0)
    val (sampleWidth, sampleHeight) = qualitySampleSize(width, height)
    val pixels = IntArray(sampleWidth * sampleHeight)
    val base = buffer.position()
    for (y in 0 until sampleHeight) {
        for (x in 0 until sampleWidth) {
            val offset = base + (top + y * height / sampleHeight) * rowStride +
                (left + x * width / sampleWidth) * pixelStride
            require(offset in base until buffer.limit())
            val value = buffer.get(offset).toInt() and 255
            pixels[y * sampleWidth + x] = (255 shl 24) or (value shl 16) or (value shl 8) or value
        }
    }
    return analysePhoto(pixels, sampleWidth, sampleHeight).verdict
}

/** Heuristics are advisory. No leaf localization, clinical refusal or automatic shutter decision. */
fun previewAdvice(verdict: PhotoVerdict): String = when (verdict) {
    PhotoVerdict.DARK -> "Кадр может быть тёмным. Добавь рассеянного света."
    PhotoVerdict.BRIGHT -> "Возможен пересвет. Убери блики и прямой свет."
    PhotoVerdict.LOW_DETAIL -> "Мало деталей. Проверь фокус и держи телефон неподвижно."
    PhotoVerdict.NOT_ASSESSED -> "Качество превью оценить не удалось. Проверь кадр самостоятельно."
    PhotoVerdict.NO_WARNING -> "По всему кадру предупреждений нет. Проверь, что лист виден целиком и в фокусе."
}

class PreviewGuidance(private val sampleIntervalMs: Long = 750, private val speechIntervalMs: Long = 8000) {
    private var lastSample: Long? = null
    private var lastSpeech: Long? = null
    private var previous: PhotoVerdict? = null
    private var consecutive = 0
    private var spoken: PhotoVerdict? = null

    fun shouldSample(now: Long): Boolean {
        if (lastSample?.let { now - it < sampleIntervalMs } == true) return false
        lastSample = now
        return true
    }

    fun accept(verdict: PhotoVerdict, now: Long): Boolean {
        consecutive = if (previous == verdict) consecutive + 1 else 1
        previous = verdict
        if (consecutive < 2) return false
        if (verdict == PhotoVerdict.NO_WARNING) {
            spoken = null
            return false
        }
        return spoken != verdict && (lastSpeech?.let { now - it >= speechIntervalMs } != false)
    }

    fun speechDelivered(verdict: PhotoVerdict, now: Long) {
        spoken = verdict
        lastSpeech = now
    }
}

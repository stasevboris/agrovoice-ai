package com.agronomy.phytomonitor

/**
 * Advisory whole-frame measurements, not a test of whether a leaf can be diagnosed.
 * Thresholds are uncalibrated heuristics on a copy with maximum side QUALITY_SIDE.
 * A smooth leaf can have little detail; a sharp background can hide a blurred leaf.
 * Never block inference or call a photo usable solely on the basis of these measurements.
 */
enum class PhotoVerdict { NO_WARNING, LOW_DETAIL, DARK, BRIGHT, NOT_ASSESSED }

data class PhotoQuality(
    val verdict: PhotoVerdict,
    val sharpness: Double?,
    val brightness: Double,
    val overexposedShare: Double,
    val sampleWidth: Int,
    val sampleHeight: Int
) {
    val advice: String?
        get() = when (verdict) {
            PhotoVerdict.NO_WARNING -> null
            PhotoVerdict.LOW_DETAIL -> "На снимке мало различимых деталей. Проверь фокус и сними лист крупнее, удерживая телефон неподвижно."
            PhotoVerdict.DARK -> "Снимок может быть слишком тёмным. Сними лист при рассеянном дневном свете, без тени от телефона."
            PhotoVerdict.BRIGHT -> "Снимок может быть пересвечен. Сними лист при рассеянном свете без бликов."
            PhotoVerdict.NOT_ASSESSED -> "Снимок слишком узкий или маленький для проверки качества. Попробуй исходное фото."
        }
}

/** Fixed scale makes measurements comparable; this is not the neural network input size. */
const val QUALITY_SIDE = 256
private const val BLUR_SHARPNESS = 60.0
private const val DARK_BRIGHTNESS = 45.0
private const val BRIGHT_BRIGHTNESS = 210.0
private const val OVEREXPOSED_SHARE = 0.25

fun qualitySampleSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(1.0, QUALITY_SIDE.toDouble() / maxOf(width, height))
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/**
 * [argb] is a row-major ARGB array of width × height pixels, e.g. `Bitmap.getPixels`.
 * Sharpness is the variance of the Laplacian on the grey channel; brightness is the mean grey level.
 */
fun analysePhoto(argb: IntArray, width: Int, height: Int): PhotoQuality {
    require(width > 0 && height > 0) { "Некорректный размер изображения" }
    require(width.toLong() * height <= argb.size) { "Недостаточно пикселей" }
    val grey = DoubleArray(width * height)
    var sum = 0.0
    var bright = 0
    for (index in 0 until width * height) {
        val pixel = argb[index]
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        val value = (299 * red + 587 * green + 114 * blue) / 1000.0
        grey[index] = value
        sum += value
        if (value >= 250.0) {
            bright++
        }
    }
    var laplacianSum = 0.0
    var laplacianSquareSum = 0.0
    var count = 0
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            val laplacian = grey[index - width] + grey[index + width] + grey[index - 1] + grey[index + 1] -
                4 * grey[index]
            laplacianSum += laplacian
            laplacianSquareSum += laplacian * laplacian
            count++
        }
    }
    val sharpness = if (count > 0) {
        val mean = laplacianSum / count
        maxOf(0.0, (laplacianSquareSum / count) - mean * mean)
    } else null
    val brightness = sum / (width * height)
    val overexposed = bright.toDouble() / (width * height)
    val verdict = when {
        sharpness == null -> PhotoVerdict.NOT_ASSESSED
        brightness < DARK_BRIGHTNESS -> PhotoVerdict.DARK
        brightness > BRIGHT_BRIGHTNESS || overexposed > OVEREXPOSED_SHARE -> PhotoVerdict.BRIGHT
        sharpness < BLUR_SHARPNESS -> PhotoVerdict.LOW_DETAIL
        else -> PhotoVerdict.NO_WARNING
    }
    return PhotoQuality(verdict, sharpness, brightness, overexposed, width, height)
}

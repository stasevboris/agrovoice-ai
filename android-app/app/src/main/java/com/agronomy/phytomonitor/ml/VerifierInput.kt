package com.agronomy.phytomonitor.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/** Matches the pilot's crop -> Pillow bilinear pad -> JPEG95 -> ImageNet normalization. */
internal object VerifierInput {
    fun prepare(source: Bitmap, box: LeafDetection, side: Int): ByteBuffer {
        val left = floor(box.left.toDouble()).toInt().coerceIn(0, source.width - 1)
        val top = floor(box.top.toDouble()).toInt().coerceIn(0, source.height - 1)
        val right = ceil(box.right.toDouble()).toInt().coerceIn(left + 1, source.width)
        val bottom = ceil(box.bottom.toDouble()).toInt().coerceIn(top + 1, source.height)
        val width = right - left
        val height = bottom - top
        val targetWidth = if (width >= height) side else round(width * side.toDouble() / height).toInt().coerceAtLeast(1)
        val targetHeight = if (height >= width) side else round(height * side.toDouble() / width).toInt().coerceAtLeast(1)
        val values = IntArray(width * height)
        source.getPixels(values, 0, width, left, top, width, height)
        val horizontal = resize(values, width, height, targetWidth, horizontal = true)
        val resized = resize(horizontal, targetWidth, height, targetHeight, horizontal = false)
        val padded = IntArray(side * side) { 0xff727272.toInt() }
        val offsetX = round((side - targetWidth) / 2.0).toInt()
        val offsetY = round((side - targetHeight) / 2.0).toInt()
        for (y in 0 until targetHeight) {
            resized.copyInto(padded, (y + offsetY) * side + offsetX, y * targetWidth, (y + 1) * targetWidth)
        }
        val frame = Bitmap.createBitmap(padded, side, side, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().use { stream ->
                check(frame.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "Не удалось подготовить вырезку" }
                stream.toByteArray()
            }
        } finally {
            frame.recycle()
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            decoded.getPixels(padded, 0, side, 0, 0, side, side)
            val buffer = ByteBuffer.allocateDirect(3 * side * side * 4).order(ByteOrder.LITTLE_ENDIAN)
            val means = floatArrayOf(0.485f, 0.456f, 0.406f)
            val deviations = floatArrayOf(0.229f, 0.224f, 0.225f)
            for ((channel, shift) in intArrayOf(16, 8, 0).withIndex()) {
                for (pixel in padded) {
                    buffer.putFloat((((pixel shr shift) and 255) / 255f - means[channel]) / deviations[channel])
                }
            }
            buffer.rewind()
            return buffer
        } finally {
            decoded.recycle()
        }
    }

    /** Separable bilinear with a widened kernel for downsampling, as used by Pillow. */
    private fun resize(input: IntArray, width: Int, height: Int, target: Int, horizontal: Boolean): IntArray {
        val original = if (horizontal) width else height
        if (original == target) return input
        val scale = original.toDouble() / target
        val support = max(1.0, scale)
        val outputWidth = if (horizontal) target else width
        val outputHeight = if (horizontal) height else target
        val output = IntArray(outputWidth * outputHeight)
        for (position in 0 until target) {
            val center = (position + 0.5) * scale
            val start = max(0, (center - support + 0.5).toInt())
            val end = minOf(original, (center + support + 0.5).toInt())
            val weights = DoubleArray(end - start) { offset ->
                max(0.0, 1.0 - kotlin.math.abs((start + offset - center + 0.5) / support))
            }
            val total = weights.sum()
            val lines = if (horizontal) height else width
            for (line in 0 until lines) {
                var color = 0xff000000.toInt()
                for (shift in intArrayOf(16, 8, 0)) {
                    var value = 0.0
                    for (offset in weights.indices) {
                        val index = if (horizontal) line * width + start + offset else (start + offset) * width + line
                        value += ((input[index] shr shift) and 255) * weights[offset]
                    }
                    color = color or (floor(value / total + 0.5).toInt().coerceIn(0, 255) shl shift)
                }
                val index = if (horizontal) line * outputWidth + position else position * outputWidth + line
                output[index] = color
            }
        }
        return output
    }
}

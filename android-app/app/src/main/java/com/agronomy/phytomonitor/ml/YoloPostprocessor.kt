package com.agronomy.phytomonitor.ml

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class LeafDetection(
    val classId: Int, val confidence: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float
)

data class Letterbox(val width: Int, val height: Int, val side: Int = 416) {
    init { require(width > 0 && height > 0 && side > 0) }
    val scale = min(side.toDouble() / width, side.toDouble() / height)
    val resizedWidth = round(width * scale).toInt().coerceAtLeast(1)
    val resizedHeight = round(height * scale).toInt().coerceAtLeast(1)
    val padX = (side - resizedWidth) / 2
    val padY = (side - resizedHeight) / 2
}

/** Ordinary YOLO Detect head: [1, 4 + classCount, candidateCount], class-aware NMS. */
object YoloPostprocessor {
    fun decode(
        output: Array<FloatArray>, geometry: Letterbox,
        threshold: Float = 0.25f, allowedClasses: Set<Int>? = null,
        iouThreshold: Float = 0.7f, classAgnostic: Boolean = false
    ): List<LeafDetection> {
        require(threshold in 0f..1f && iouThreshold in 0f..1f)
        require(output.size > 4 && output.all { it.size == output[0].size })
        val classes = allowedClasses?.filter { it in 0 until output.size - 4 }?.sorted()?.toIntArray()
            ?: IntArray(output.size - 4) { it }
        if (classes.isEmpty()) return emptyList()
        val candidates = mutableListOf<LeafDetection>()
        for (index in output[0].indices) {
            var bestClass = classes[0]
            var score = output[bestClass + 4][index]
            for (position in 1 until classes.size) {
                val cls = classes[position]
                if (output[cls + 4][index] > score) {
                    score = output[cls + 4][index]
                    bestClass = cls
                }
            }
            if (!score.isFinite() || score < threshold) continue
            val x = output[0][index]; val y = output[1][index]
            val w = output[2][index]; val h = output[3][index]
            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) continue
            // NMS uses original, unclipped boxes, matching the desktop detector.
            candidates.add(LeafDetection(bestClass, score, x-w/2, y-h/2, x+w/2, y+h/2))
        }
        val kept = mutableListOf<LeafDetection>()
        for (candidate in candidates.sortedByDescending { it.confidence }.take(30_000)) {
            if (kept.none { (classAgnostic || it.classId == candidate.classId) && iou(it, candidate) > iouThreshold }) kept.add(candidate)
            if (kept.size == 300) break
        }
        return kept.mapNotNull { box ->
            val mapped = box.copy(
                left=((box.left-geometry.padX)/geometry.scale).toFloat().coerceIn(0f, geometry.width.toFloat()),
                top=((box.top-geometry.padY)/geometry.scale).toFloat().coerceIn(0f, geometry.height.toFloat()),
                right=((box.right-geometry.padX)/geometry.scale).toFloat().coerceIn(0f, geometry.width.toFloat()),
                bottom=((box.bottom-geometry.padY)/geometry.scale).toFloat().coerceIn(0f, geometry.height.toFloat())
            )
            mapped.takeIf { it.right > it.left && it.bottom > it.top }
        }
    }

    private fun iou(a: LeafDetection, b: LeafDetection): Float {
        val intersection = max(0f,min(a.right,b.right)-max(a.left,b.left)) * max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        val areaA=max(0f,a.right-a.left)*max(0f,a.bottom-a.top)
        val areaB=max(0f,b.right-b.left)*max(0f,b.bottom-b.top)
        return intersection/(areaA+areaB-intersection+1e-9f)
    }
}

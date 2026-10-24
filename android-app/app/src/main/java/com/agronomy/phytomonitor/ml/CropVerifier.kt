package com.agronomy.phytomonitor.ml

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/** Fixed experimental crop/reject policy. It never replaces a detector class or its score. */
data class CropVerifierGraph(
    val id: String,
    val asset: String,
    val sha256: String,
    val side: Int,
    val crops: List<String>,
    val threshold: Double,
    val detectorThreshold: Float,
    val maxLeaves: Int
) {
    fun score(logits: FloatArray, crop: String): Double {
        require(logits.size == crops.size && logits.all { it.isFinite() }) { "Некорректный выход проверки культуры" }
        val index = crops.indexOf(crop)
        require(index >= 0 && crop != "__reject__") { "Культура не поддерживается проверяющей моделью" }
        val delta = logits[index].toDouble() - logits[crops.indexOf("__reject__")].toDouble()
        return if (delta >= 0) 1.0 / (1.0 + exp(-delta)) else exp(delta) / (1.0 + exp(delta))
    }

    fun accepts(logits: FloatArray, crop: String): Boolean = score(logits, crop) >= threshold

    fun verify(proposals: List<LeafDetection>, classCrops: List<String>,
               logits: (Int, LeafDetection) -> FloatArray): Pair<List<LeafDetection>, VerificationSummary> {
        val accepted = mutableListOf<LeafDetection>()
        val decisions = proposals.take(maxLeaves).mapIndexed { index, leaf ->
            val crop = classCrops[leaf.classId]
            val score = score(logits(index, leaf), crop)
            val keep = score >= threshold
            if (keep) accepted += leaf
            VerificationDecision(index, crop, score, keep, leaf)
        }
        return accepted to VerificationSummary(proposals.size, decisions.size, proposals.size - decisions.size,
            decisions.count { !it.accepted }, accepted.size, decisions)
    }

    fun decode(output: Array<FloatArray>, geometry: Letterbox, classCrops: List<String>, crop: String): List<LeafDetection> =
        YoloPostprocessor.decode(output, geometry, detectorThreshold).filter {
            crop == "all" || classCrops[it.classId] == crop
        }

    companion object {
        fun parse(data: JSONObject, detectorCrops: List<String>): CropVerifierGraph {
            require(data.getString("policy") == "crop_vs_reject_v1") { "Неизвестное правило отказа" }
            val asset = data.getString("asset")
            require(Regex("models/[A-Za-z0-9_.-]+\\.onnx").matches(asset)) { "Неверный путь verifier" }
            val digest = data.getString("sha256")
            require(Regex("[0-9a-f]{64}").matches(digest)) { "Неверный SHA verifier" }
            val side = data.getInt("side")
            require(side == 224) { "Неподдерживаемый размер verifier" }
            val values = data.getJSONArray("crops")
            val crops = List(values.length()) { values.getString(it) }
            require(crops.distinct().size == crops.size && crops.toSet() == detectorCrops.toSet() + "__reject__") {
                "Культуры verifier не соответствуют детектору"
            }
            val threshold = data.getDouble("threshold")
            require(threshold.isFinite() && threshold in 0.0..1.0) { "Неверный порог отказа" }
            val detectorThreshold = data.getDouble("detector_threshold")
            require(detectorThreshold == 0.25) { "Изменение порога детектора требует нового опыта" }
            val maxLeaves = data.optInt("max_leaves", 8)
            require(maxLeaves in 1..32) { "Неверный лимит verifier" }
            return CropVerifierGraph(data.getString("id"), asset, digest, side, crops, threshold, detectorThreshold.toFloat(), maxLeaves)
        }
    }
}

data class VerificationDecision(val proposal: Int, val crop: String, val score: Double, val accepted: Boolean,
                                val detector: LeafDetection? = null) {
    fun toJson(): JSONObject = JSONObject().put("proposal", proposal).put("crop", crop)
        .put("score", score).put("accepted", accepted)
        .put("detector", detector?.let { leaf ->
            JSONObject().put("class_id", leaf.classId).put("score", leaf.confidence.toDouble())
                .put("xyxy", JSONArray(listOf(leaf.left, leaf.top, leaf.right, leaf.bottom)))
        } ?: JSONObject.NULL)
}

data class VerificationSummary(
    val proposed: Int,
    val checked: Int,
    val skipped: Int,
    val rejected: Int,
    val accepted: Int,
    val decisions: List<VerificationDecision>
) {
    fun message(): String = "Экспериментальная проверка культуры: проверено $checked из $proposed, отклонено $rejected. " +
        (if (skipped > 0) "Не проверено $skipped; сними один лист крупнее. " else "") +
        "Отказ не определяет причину: неизвестная культура, неподходящий кадр или ошибка проверки. Диагноз болезни не проверен."

    fun toJson(): JSONObject = JSONObject().put("proposed", proposed).put("checked", checked)
        .put("skipped", skipped).put("rejected", rejected).put("accepted", accepted)
        .put("decisions", JSONArray(decisions.map { it.toJson() }))

    companion object {
        fun fromJson(json: JSONObject): VerificationSummary {
            val rows = json.getJSONArray("decisions")
            return VerificationSummary(json.getInt("proposed"), json.getInt("checked"), json.getInt("skipped"),
                json.getInt("rejected"), json.getInt("accepted"), List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    val detector = row.optJSONObject("detector")?.let { leaf ->
                        val box = leaf.getJSONArray("xyxy")
                        LeafDetection(leaf.getInt("class_id"), leaf.getDouble("score").toFloat(),
                            box.getDouble(0).toFloat(), box.getDouble(1).toFloat(), box.getDouble(2).toFloat(), box.getDouble(3).toFloat())
                    }
                    VerificationDecision(row.getInt("proposal"), row.getString("crop"), row.getDouble("score"), row.getBoolean("accepted"), detector)
                })
        }
    }
}

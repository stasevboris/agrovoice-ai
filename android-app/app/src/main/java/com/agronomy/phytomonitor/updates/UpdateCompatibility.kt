package com.agronomy.phytomonitor.updates

import com.agronomy.phytomonitor.ml.DetectorModel
import com.agronomy.phytomonitor.ml.ModelCatalog
import org.json.JSONArray
import org.json.JSONObject

object UpdateCompatibility {
    fun model(release: UpdateRelease, bundled: List<DetectorModel>, canonical: List<String>): DetectorModel {
        require(release.kind == "model" && release.metadata.optString("prepared_by") == "prepare_model_release.py") {
            "Нет метаданных подготовленного выпуска модели"
        }
        val entry = release.metadata.getJSONObject("manifest_entry")
        require(entry.optString("family") == "yolo" && !entry.has("classifier") && entry.optString("sha256") == release.sha256) {
            "Модель или её SHA не поддерживается"
        }
        val model = ModelCatalog.parse(JSONObject().put("schema", 3).put("models", JSONArray().put(entry))).single()
        require(model.id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}")) && model.side in setOf(320, 416, 640)) {
            "Неподдерживаемое имя или разрешение модели"
        }
        val sameId = bundled.firstOrNull { it.id == model.id }
        val canonicalOrder = canonical.isNotEmpty() && model.classes == canonical && model.dataset == "allcrops_v1"
        val existingOrder = sameId != null && model.classes == sameId.classes && model.labels == sameId.labels &&
            sameId.family == "yolo" && sameId.classifier == null
        require((canonicalOrder || existingOrder) && (sameId == null || existingOrder)) {
            "Порядок классов модели не совместим с приложением"
        }
        return model.copy(asset = "updates/${release.sha256}.onnx",
            taxonomyVersion = if (canonicalOrder) "taxonomy_v1" else sameId?.taxonomyVersion)
    }

    fun supportedOutput(shape: LongArray, classCount: Int): Boolean =
        shape.size == 3 && shape[0] == 1L && shape[1] == (classCount + 4).toLong() && shape[2] > 0L
}

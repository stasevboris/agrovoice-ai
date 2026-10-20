package com.agronomy.phytomonitor.ml

/** Automatic update rollback must not broaden the user's crop filter. */
object ModelCropPolicy {
    fun restore(savedCrop: String, model: DetectorModel, automaticFallback: Boolean): String = when {
        savedCrop == "all" -> "all"
        model.family == "detr" -> "all"
        savedCrop in model.crops || automaticFallback -> savedCrop
        else -> "all"
    }

    fun requireSupported(model: DetectorModel, crop: String) {
        require(model.family != "yolo" || crop == "all" || crop in model.crops) {
            val name = CropVocabulary.names[crop] ?: crop
            "Модель ${model.name} не поддерживает культуру «$name». Проверьте обновления или выберите другую культуру."
        }
    }
}

package com.agronomy.phytomonitor.journal

import org.json.JSONObject
import java.io.File

const val MAX_INSPECTION_FRAMES = 10

/** User-described camera angle, not a diagnosis or an expert label. */
enum class FrameRole(val value: String) {
    UNSPECIFIED("unspecified"), OVERVIEW("overview"), FRONT("front"), BACK("back");

    companion object {
        fun from(value: String): FrameRole = entries.firstOrNull { it.value == value }
            ?: throw JournalFormatException("неизвестный ракурс кадра")
    }
}

/** IDs belong to the caller's draft and must remain stable across retries. */
data class InspectionFrame(val id: String, val report: File, val role: FrameRole = FrameRole.UNSPECIFIED)

data class ParsedFrame(
    val id: String,
    val order: Int,
    val role: FrameRole,
    val photoPath: String,
    val photoSha256: String,
    val width: Int,
    val height: Int,
    val reportName: String,
    val reportSha256: String,
    val predictionJson: String?,
    val plant: String = "",
    val plantId: String? = null,
    val archivePhoto: String? = null,
    val storedDetectionsJson: String? = null
) {
    val prediction: JSONObject? get() = predictionJson?.let(::JSONObject)
}

fun InspectionWithDetails.orderedPhotos(): List<PhotoWithDetections> = photos.sortedWith(
    compareBy<PhotoWithDetections> { it.photo.frameOrder }
        .thenBy { if (it.photo.id == inspection.id) 0 else 1 }.thenBy { it.photo.id }
)

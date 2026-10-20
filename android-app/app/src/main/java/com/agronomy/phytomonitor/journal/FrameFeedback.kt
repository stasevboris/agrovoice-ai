package com.agronomy.phytomonitor.journal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.json.JSONObject

/** A user's opinion about one immutable observation. Never an expert diagnosis. */
@Entity(tableName = "frame_feedback", foreignKeys = [ForeignKey(entity = PhotoEntity::class,
    parentColumns = ["id"], childColumns = ["photo_id"], onDelete = ForeignKey.CASCADE)])
data class FrameFeedback(
    @PrimaryKey @ColumnInfo(name = "photo_id") val photoId: String,
    @ColumnInfo(name = "photo_sha256") val photoSha256: String,
    @ColumnInfo(name = "report_sha256") val reportSha256: String,
    @ColumnInfo(name = "analysis_version") val analysisVersion: Int?,
    val verdict: String,
    val correction: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val revision: Int,
    val source: String = "user"
) {
    fun validate() {
        require(verdict in setOf("correct", "incorrect", "unknown")) { "Неизвестная оценка кадра." }
        require(correction.length <= 500 && (verdict == "incorrect" || correction.isEmpty())) { "Исправление допускается только для неверного результата, до 500 символов." }
        require(source == "user" && revision > 0 && updatedAt >= 0 && (analysisVersion == null || analysisVersion > 0))
    }

    /** An opinion without a version is kept; a version must name the analysis actually stored. */
    fun matchesAnalysis(prediction: JSONObject?): Boolean = analysisVersion == null || analysisVersion == versionOf(prediction)

    fun toJson(): JSONObject = JSONObject().put("photo_sha256", photoSha256)
        .put("report_sha256", reportSha256).put("analysis_version", analysisVersion ?: JSONObject.NULL)
        .put("verdict", verdict).put("correction", correction).put("updated_at", updatedAt)
        .put("revision", revision).put("source", source)

    companion object {
        /** schema_version of a saved result; null for results saved before provenance existed. */
        fun versionOf(prediction: JSONObject?): Int? =
            prediction?.optJSONObject("result_provenance")?.optInt("schema_version")?.takeIf { it > 0 }

        fun evidenceSha(value: String): String = value.takeIf { Regex("[0-9a-f]{64}").matches(it) }.orEmpty()

        fun fromJson(photoId: String, json: JSONObject): FrameFeedback {
            try {
                for (field in listOf("updated_at", "revision", "analysis_version")) {
                    if (field == "analysis_version" && json.isNull(field)) continue
                    val value = json.opt(field) as? Number ?: throw IllegalArgumentException("Неверная версия оценки.")
                    require(value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble())
                    if (field != "updated_at") require(value.toLong() in 1..Int.MAX_VALUE.toLong())
                }
                return FrameFeedback(photoId, json.getString("photo_sha256"), json.getString("report_sha256"),
                    if (json.isNull("analysis_version")) null else json.getInt("analysis_version"),
                    json.getString("verdict"), json.getString("correction"), json.getLong("updated_at"),
                    json.getInt("revision"), json.getString("source")).also { it.validate() }
            } catch (error: IllegalArgumentException) {
                throw JournalFormatException(error.message ?: "Неверная оценка кадра.")
            }
        }
    }
}

package com.agronomy.phytomonitor

import android.content.Context
import com.agronomy.phytomonitor.ml.FieldDetector
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class InspectionEntry(val id: String,val created: Long,val photo: String,val model: String,val note: String,val review: String,val detections: Int,val plot: String="")

/** Explicit user-reviewed records, separate from automatically saved inference JSON. */
class InspectionJournal(context: Context) {
    private val root=File(context.getExternalFilesDir(null) ?: context.filesDir,"inspections").apply {mkdirs()}
    fun save(report: File,note: String,review: String,plot: String=""): InspectionEntry {
        require(review in setOf("unrated","correct","incorrect","uncertain"))
        require(note.length<=4000)
        require(plot.length<=120)
        val prediction=JSONObject(report.readText())
        val previous=root.listFiles()?.filter {it.extension=="json"}?.firstOrNull {runCatching {JSONObject(it.readText()).getString("report_name")==report.name}.getOrDefault(false)}
        val id=previous?.nameWithoutExtension ?: UUID.randomUUID().toString()
        val old=previous?.let {JSONObject(it.readText())}
        val data=JSONObject().put("id",id).put("created_epoch_ms",old?.getLong("created_epoch_ms") ?: System.currentTimeMillis())
            .put("updated_epoch_ms",System.currentTimeMillis()).put("report_name",report.name)
            .put("report_sha256",FieldDetector.sha256(report)).put("prediction",prediction)
            .put("note",note).put("review",review).put("plot",plot)
            .put("review_scope","User assessment of this entire photo/result, not verified leaf labels or training ground truth")
        val temporary=File(root,"$id.tmp");temporary.writeText(data.toString(2))
        val target=File(root,"$id.json")
        require(temporary.renameTo(target)){"Не удалось сохранить осмотр"}
        return entry(data)
    }
    fun list(): List<InspectionEntry> = root.listFiles()?.filter {it.extension=="json"}?.mapNotNull {runCatching {entry(JSONObject(it.readText()))}.getOrNull()}?.sortedByDescending {it.created} ?: emptyList()
    fun export(): String {
        val records=JSONArray()
        list().forEach {records.put(JSONObject(File(root,"${it.id}.json").readText()))}
        return JSONObject().put("schema","agrovoice-inspections-v1").put("exported_epoch_ms",System.currentTimeMillis())
            .put("contains_photo_bytes",false).put("records",records).toString(2)
    }
    private fun entry(data: JSONObject): InspectionEntry {
        val prediction=data.getJSONObject("prediction")
        return InspectionEntry(data.getString("id"),data.getLong("created_epoch_ms"),prediction.getString("photo"),prediction.getString("model"),data.getString("note"),data.getString("review"),prediction.getJSONArray("detections").length(),data.optString("plot"))
    }
}

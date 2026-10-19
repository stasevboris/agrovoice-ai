package com.agronomy.phytomonitor.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import com.agronomy.phytomonitor.PhotoQuality
import com.agronomy.phytomonitor.analysePhoto
import com.agronomy.phytomonitor.qualitySampleSize

object PhotoFiles {
    const val MAX_DECODE_SIDE = 2048
    /** Called on the analysis worker, before inference. The displayed source is never recycled. */
    fun measureQuality(bitmap: Bitmap): PhotoQuality {
        val (width, height) = qualitySampleSize(bitmap.width, bitmap.height)
        val sample = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)
            return analysePhoto(pixels, width, height)
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    fun folder(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir,"photos").apply { mkdirs() }
    fun newCapture(context: Context) = File(folder(context),"camera_${UUID.randomUUID()}.jpg")

    /**
     * Copies a picked or captured photo into the app folder once: the file is named by its SHA-256, so
     * choosing the same photo again reuses the existing copy instead of adding another one.
     */
    fun import(context: Context, uri: Uri): File {
        val extension=when(context.contentResolver.getType(uri)) {
            "image/png"->"png"; "image/webp"->"webp"; "image/heic","image/heif"->"heic"; else->"jpg"
        }
        val folder=folder(context)
        val temporary=File(folder,"incoming_${UUID.randomUUID()}.part")
        try {
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            val input=context.contentResolver.openInputStream(uri) ?: error("Фото недоступно")
            input.use { source -> temporary.outputStream().use { destination ->
                var total=0L; val chunk=ByteArray(64*1024)
                while(true){
                    val read=source.read(chunk); if(read<0)break
                    total+=read; require(total<=64L*1024*1024){"Фото больше 64 МБ"}
                    digest.update(chunk,0,read)
                    destination.write(chunk,0,read)
                }
            } }
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            val target=File(folder,"import_${hash.take(32)}.$extension")
            if(target.isFile && target.length()==temporary.length()){temporary.delete();return target}
            require(temporary.renameTo(target)){"Не удалось сохранить фото"}
            return target
        } catch(error: Exception){temporary.delete();throw error}
    }

    /** Small preview of a saved photo for the journal list. */
    fun thumbnail(file: File, maxSize: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 29) {
            return ThumbnailUtils.createImageThumbnail(file, Size(maxSize, maxSize), null)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) > maxSize * sample * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options) ?: error("Не удалось открыть фото")
    }
    fun decode(file: File): Bitmap {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.absolutePath,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0){"Не удалось прочитать фото. Попробуйте JPEG или PNG."}
        var sample=1
        while(max(bounds.outWidth,bounds.outHeight).toLong()>MAX_DECODE_SIDE.toLong()*sample)sample*=2
        val options=BitmapFactory.Options().apply {
            inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888
            inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded=BitmapFactory.decodeFile(file.absolutePath,options) ?: error("Не удалось открыть фото")
        val orientation=try { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) } catch(_:Exception){ExifInterface.ORIENTATION_NORMAL}
        val matrix=Matrix()
        when(orientation){
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL->matrix.setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180->matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL->matrix.setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE->{matrix.setRotate(90f);matrix.postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_90->matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE->{matrix.setRotate(270f);matrix.postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_270->matrix.setRotate(270f)
        }
        if(matrix.isIdentity)return decoded
        return Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also { if(it!==decoded)decoded.recycle() }
    }

    fun saveResult(context: Context, photo: File, bitmap: Bitmap, result: DetectionResult,
                   detector: FieldDetector, crop: String, threshold: Float): File =
        saveResult(context, photo, bitmap, result, detector.manifest, crop, threshold)

    /** Metadata-only overload: saving a completed result never needs a live ONNX session. */
    internal fun saveResult(context: Context, photo: File, bitmap: Bitmap, result: DetectionResult,
                            manifest: JSONObject, crop: String, threshold: Float): File {
        val directory=File(context.getExternalFilesDir(null) ?: context.filesDir,"results").apply{mkdirs()}
        val boxes=JSONArray()
        result.boxes.forEach { box->boxes.put(JSONObject()
            .put("class_id",box.classId).put("class_name",result.model.classes[box.classId])
            .put("label_ru",result.model.labels[box.classId]).put("confidence",box.confidence.toDouble())
            .put("xyxy_pixels",JSONArray(listOf(box.left,box.top,box.right,box.bottom)))) }
        val report=JSONObject().put("created_epoch_ms",System.currentTimeMillis())
            .put("photo",photo.absolutePath).put("photo_sha256",FieldDetector.sha256(photo))
            .put("decoded_width",bitmap.width).put("decoded_height",bitmap.height)
            .put("coordinate_space","EXIF-oriented decoded image, maximum side $MAX_DECODE_SIDE")
            .put("model",result.model.id).put("model_sha256",result.model.sha256)
            .put("dataset",result.model.dataset).put("seed",result.model.seed).put("provisional",result.model.provisional)
            .put("detector_seed",result.model.detectorSeed).put("family",result.model.family)
            .put("proposed_leaves",result.proposedLeaves).put("classified_leaves",result.classifiedLeaves).put("skipped_leaves",result.skippedLeaves)
            .put("score_policy",if(result.model.classifier==null) "detector class score" else "detector maximum class score times classifier softmax; uncalibrated")
            .put("model_metadata",manifest.getJSONArray("models").let {array ->
                (0 until array.length()).map {array.getJSONObject(it)}.first {it.getString("id")==result.model.id}
            })
            .put("crop_filter",crop).put("confidence_threshold",threshold.toDouble())
            .put("crop_policy", if (crop == "all") "model_default" else "rank_within_crop_without_score_renormalization")
            .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown")
            .put("inference_ms",result.inferenceMs).put("pipeline_ms",result.pipelineMs)
            .put("detections",boxes).put("device","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("scope","Whole leaf with crop/condition. Confidence is not disease probability or damaged area.")
        addAnalysisAssessment(report, result.boxes.size, result.photoQuality)
        report.put("result_provenance", ResultProvenanceSnapshot.create(
            result.model, bitmap.width, bitmap.height, crop, threshold, report.getJSONObject("model_metadata")
        ))
        return File(directory,"${System.currentTimeMillis()}_${result.model.id}_${UUID.randomUUID().toString().take(8)}.json")
            .apply {writeText(report.toString(2))}
    }
}

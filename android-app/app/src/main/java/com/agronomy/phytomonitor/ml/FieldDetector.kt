package com.agronomy.phytomonitor.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONObject
import com.agronomy.phytomonitor.PhotoQuality
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

data class ClassifierGraph(val id: String, val asset: String, val sha256: String, val seed: Int,
                           val kind: String, val side: Int = 224)
data class DetectorModel(val id: String, val name: String, val asset: String, val sha256: String,
                         val family: String = "yolo", val side: Int = 416,
                         val seed: Int = 20261003, val detectorSeed: Int = seed,
                         val dataset: String = "clean v2", val provisional: Boolean = true,
                         val classifier: ClassifierGraph? = null,
                         /** Output classes of this graph, their Russian labels and crop ids (manifest schema 3). */
                         val classes: List<String> = emptyList(), val labels: List<String> = emptyList(),
                         val classCrops: List<String> = emptyList(), val crops: List<String> = emptyList(),
                         val cropNames: Map<String,String> = emptyMap())
data class DetectionResult(val model: DetectorModel, val boxes: List<LeafDetection>,
                           val inferenceMs: Double, val pipelineMs: Double,
                           val proposedLeaves: Int = 0, val classifiedLeaves: Int = 0,
                           val skippedLeaves: Int = 0,
                           val photoQuality: PhotoQuality? = null)

/** One background executor, at most one detector and one classifier session. */
class FieldDetector(private val context: Context) : Closeable {
    val manifest = JSONObject(context.assets.open("models/manifest.json").bufferedReader().use { it.readText() })
    /** Each model carries its own classes and labels; old schema-2 models share the top-level list. */
    val models = ModelCatalog.parse(manifest)
    private val environment=OrtEnvironment.getEnvironment()
    private val sessions=mutableMapOf<String,OrtSession>()
    private var currentModel: String? = null

    private val modelFolder by lazy { File(context.filesDir,"models").apply {mkdirs();removeStaleCopies(this)} }

    /** One copy per graph file: several modes share yolo26n.onnx, so the name is the asset digest, not the mode id. */
    private fun modelFile(asset: String, digest: String): File {
        val file=File(modelFolder,"$digest.onnx")
        val verified=File(modelFolder,"$digest.verified")
        if(file.isFile && verified.isFile && verified.readText()==file.length().toString())return file
        val part=File(modelFolder,"$digest.part")
        context.assets.open(asset).use {source -> part.outputStream().use {source.copyTo(it)}}
        require(sha256(part)==digest){"Повреждён файл модели"}
        file.delete()
        check(part.renameTo(file)){"Не удалось сохранить модель"}
        verified.writeText(file.length().toString())
        return file
    }

    /** Deletes copies left by older app versions (id-prefixed names, superseded digests). */
    private fun removeStaleCopies(folder: File) {
        val wanted=models.flatMap {listOfNotNull(it.sha256,it.classifier?.sha256)}.toSet()
        folder.listFiles()?.filter {it.nameWithoutExtension !in wanted}?.forEach {it.delete()}
    }

    private fun graph(id: String, asset: String, digest: String, side: Int, shape: (LongArray)->Boolean): OrtSession {
        sessions[id]?.let {return it}
        val file=modelFile(asset,digest)
        val loaded=OrtSession.SessionOptions().use {
            it.setIntraOpNumThreads(4);it.setInterOpNumThreads(1)
            environment.createSession(file.absolutePath,it)
        }
        try{
            require(loaded.inputNames.size==1){"Неверное число входов"}
            val input=(loaded.inputInfo[loaded.inputNames.first()]!!.info as TensorInfo).shape
            require(input.contentEquals(longArrayOf(1,3,side.toLong(),side.toLong()))){"Неверный вход модели"}
            require(loaded.outputNames.size==1){"Неверное число выходов"}
            val output=(loaded.outputInfo[loaded.outputNames.first()]!!.info as TensorInfo).shape
            require(shape(output)){"Неверный выход модели"}
        }catch(error:Exception){loaded.close();throw error}
        sessions[id]=loaded;return loaded
    }

    fun detect(bitmap: Bitmap, model: DetectorModel, crop: String, threshold: Float,
               trace: ((ByteBuffer, Array<FloatArray>) -> Unit)? = null,
               stageTrace: ((String, ByteBuffer, FloatArray, IntArray)->Unit)? = null): DetectionResult {
        require(threshold in 0f..1f)
        val started=SystemClock.elapsedRealtimeNanos()
        if(currentModel!=model.id){close();currentModel=model.id}
        val detector=graph(model.id,model.asset,model.sha256,model.side){shape ->
            if(model.family=="detr")shape.contentEquals(longArrayOf(1,300,6))
            else shape.size==3 && shape[0]==1L && shape[1]==(model.classes.size+4).toLong() && shape[2]>0
        }
        val classifier=model.classifier?.let {c -> graph(c.id,c.asset,c.sha256,c.side){it.contentEquals(longArrayOf(1,model.classes.size.toLong()))}}
        val geometry=Letterbox(bitmap.width,bitmap.height,model.side)
        val input=prepareInput(bitmap,geometry)
        // The crop of each class comes from the manifest, not from the beginning of an English class name.
        val allowed=if(crop=="all") null else model.classCrops.indices.filter {model.classCrops[it]==crop}.toSet()
        var inferenceNanos=0L
        val proposals=OnnxTensor.createTensor(environment,input.asFloatBuffer(),longArrayOf(1,3,model.side.toLong(),model.side.toLong())).use {tensor ->
            val before=SystemClock.elapsedRealtimeNanos()
            detector.run(mapOf(detector.inputNames.first() to tensor)).use {result ->
                inferenceNanos+=SystemClock.elapsedRealtimeNanos()-before
                @Suppress("UNCHECKED_CAST")
                val output=((result[0] as OnnxTensor).value as Array<Array<FloatArray>>)[0]
                trace?.invoke(input.asReadOnlyBuffer(),output)
                stageTrace?.invoke("detector",input.asReadOnlyBuffer(),output.flatMap {it.asIterable()}.toFloatArray(),intArrayOf(1,output.size,output[0].size))
                if(model.family=="detr")decodeDetr(output,geometry,threshold,allowed,model.classes.size)
                else YoloPostprocessor.decode(output,geometry,if(classifier==null)threshold else .10f,
                    if(classifier==null)allowed else null,classAgnostic=classifier!=null)
            }
        }
        if(classifier==null)return DetectionResult(model,proposals,inferenceNanos/1e6,(SystemClock.elapsedRealtimeNanos()-started)/1e6)
        val predicted=mutableListOf<LeafDetection>()
        // Explicit ceiling keeps multi-leaf photos usable on older phones; skipped count is reported.
        val selected=proposals.take(MAX_CLASSIFIED_LEAVES)
        val c=model.classifier!!
        for((index,leaf) in selected.withIndex()){
            val leafInput=prepareLeaf(bitmap,leaf,c.side)
            OnnxTensor.createTensor(environment,leafInput.asFloatBuffer(),longArrayOf(1,3,c.side.toLong(),c.side.toLong())).use {tensor ->
                val before=SystemClock.elapsedRealtimeNanos()
                classifier.run(mapOf(classifier.inputNames.first() to tensor)).use {result ->
                    inferenceNanos+=SystemClock.elapsedRealtimeNanos()-before
                    @Suppress("UNCHECKED_CAST")
                    val logits=((result[0] as OnnxTensor).value as Array<FloatArray>)[0]
                    require(logits.size==model.classes.size && logits.all {it.isFinite()}){"Некорректный выход классификатора"}
                    if(index==0)stageTrace?.invoke("classifier",leafInput.asReadOnlyBuffer(),logits.copyOf(),intArrayOf(1,logits.size))
                    val largest=logits.maxOrNull()!!
                    val probabilities=logits.map {exp((it-largest).toDouble())};val total=probabilities.sum()
                    val cls=logits.indices.maxByOrNull {logits[it]}!!
                    val score=(leaf.confidence*probabilities[cls]/total).toFloat()
                    if(score>=threshold && (allowed==null || cls in allowed))predicted+=leaf.copy(classId=cls,confidence=score)
                }
            }
        }
        return DetectionResult(model,predicted.sortedByDescending {it.confidence},inferenceNanos/1e6,
            (SystemClock.elapsedRealtimeNanos()-started)/1e6,proposals.size,selected.size,proposals.size-selected.size)
    }

    fun prepareInput(bitmap: Bitmap, geometry: Letterbox=Letterbox(bitmap.width,bitmap.height)): ByteBuffer {
        val frame=Bitmap.createBitmap(geometry.side,geometry.side,Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(114,114,114))
            drawBitmap(bitmap,null,Rect(geometry.padX,geometry.padY,geometry.padX+geometry.resizedWidth,geometry.padY+geometry.resizedHeight),Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return pixels(frame).also {frame.recycle()}
    }

    private fun prepareLeaf(bitmap: Bitmap, b: LeafDetection, side: Int): ByteBuffer {
        val px=(b.right-b.left)*.1f;val py=(b.bottom-b.top)*.1f
        val l=floor((b.left-px).toDouble()).toInt().coerceIn(0,bitmap.width-1)
        val t=floor((b.top-py).toDouble()).toInt().coerceIn(0,bitmap.height-1)
        val r=ceil((b.right+px).toDouble()).toInt().coerceIn(l+1,bitmap.width)
        val bottom=ceil((b.bottom+py).toDouble()).toInt().coerceIn(t+1,bitmap.height)
        val w=r-l;val h=bottom-t;val square=max(w,h)
        val frame=Bitmap.createBitmap(square,square,Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(114,114,114))
            val x=(square-w)/2;val y=(square-h)/2
            drawBitmap(bitmap,Rect(l,t,r,bottom),Rect(x,y,x+w,y+h),null)
        }
        val resized=Bitmap.createScaledBitmap(frame,side,side,true)
        return pixels(resized).also {if(resized!==frame)resized.recycle();frame.recycle()}
    }

    private fun pixels(frame: Bitmap): ByteBuffer {
        val count=frame.width*frame.height;val values=IntArray(count)
        frame.getPixels(values,0,frame.width,0,0,frame.width,frame.height)
        val buffer=ByteBuffer.allocateDirect(3*count*4).order(ByteOrder.LITTLE_ENDIAN)
        for(shift in intArrayOf(16,8,0))for(pixel in values)buffer.putFloat(((pixel shr shift) and 255)/255f)
        buffer.rewind();return buffer
    }

    private fun decodeDetr(rows: Array<FloatArray>,g: Letterbox,threshold: Float,allowed: Set<Int>?,classCount: Int): List<LeafDetection> =
        rows.mapNotNull {r ->
            require(r.size==6 && r.all {it.isFinite()}){"Некорректный выход DETR"}
            val cls=r[5].toInt()
            if(cls !in 0 until classCount || r[4]<threshold || (allowed!=null && cls !in allowed))null
            else {
                val l=((r[0]-g.padX)/(g.resizedWidth.toFloat()/g.width)).coerceIn(0f,g.width.toFloat())
                val t=((r[1]-g.padY)/(g.resizedHeight.toFloat()/g.height)).coerceIn(0f,g.height.toFloat())
                val right=((r[2]-g.padX)/(g.resizedWidth.toFloat()/g.width)).coerceIn(0f,g.width.toFloat())
                val bottom=((r[3]-g.padY)/(g.resizedHeight.toFloat()/g.height)).coerceIn(0f,g.height.toFloat())
                if(right<=l || bottom<=t)null else LeafDetection(cls,r[4],l,t,right,bottom)
            }
        }.sortedByDescending {it.confidence}

    override fun close(){sessions.values.forEach {it.close()};sessions.clear();currentModel=null}

    companion object {
        const val MAX_CLASSIFIED_LEAVES=32
        fun sha256(file: File): String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input ->
                val bytes=ByteArray(64*1024)
                while(true){val count=input.read(bytes);if(count<0)break;digest.update(bytes,0,count)}
            }
            return digest.digest().joinToString(""){"%02x".format(it)}
        }
    }
}

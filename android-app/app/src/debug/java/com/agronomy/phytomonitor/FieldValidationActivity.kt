package com.agronomy.phytomonitor

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.agronomy.phytomonitor.ml.FieldDetector
import com.agronomy.phytomonitor.ml.PhotoFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Debug-only integration check, limited to photos already in this app's directory. */
class FieldValidationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val text=TextView(this).apply {textSize=18f;text="Проверяю фото и модели…"}
        setContentView(text)
        Thread {
            val directory=File(getExternalFilesDir(null),"field_validation").apply {mkdirs()}
            try {
                val names=intent.getStringExtra("photos") ?: error("Нет списка фото")
                val folder=PhotoFiles.folder(this).canonicalFile
                val reports=JSONArray()
                FieldDetector(this).use { detector ->
                    for ((index,name) in names.split(',').withIndex()) {
                        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
                        val file=File(folder,name).canonicalFile
                        require(file.parentFile==folder && file.isFile)
                        val bitmap=PhotoFiles.decode(file)
                        try {
                            for(model in detector.models) {
                                val prefix="${index}_${model.id}"
                                val result=detector.detect(bitmap,model,"all",0.25f,
                                    trace=if(index==0) { input,output ->
                                        val bytes=ByteArray(input.remaining());input.get(bytes)
                                        File(directory,"${prefix}_input.bin").writeBytes(bytes)
                                        val raw=ByteBuffer.allocate(output.sumOf {it.size}*4).order(ByteOrder.LITTLE_ENDIAN)
                                        output.forEach {row -> row.forEach {raw.putFloat(it)}}
                                        File(directory,"${prefix}_output.bin").writeBytes(raw.array())
                                    } else null,
                                    stageTrace=if(index==0) {stage,input,output,shape ->
                                        val bytes=ByteArray(input.remaining());input.get(bytes)
                                        File(directory,"${prefix}_${stage}_input.bin").writeBytes(bytes)
                                        val raw=ByteBuffer.allocate(output.size*4).order(ByteOrder.LITTLE_ENDIAN)
                                        output.forEach {raw.putFloat(it)}
                                        File(directory,"${prefix}_${stage}_output.bin").writeBytes(raw.array())
                                        File(directory,"${prefix}_${stage}_shape.json").writeText(JSONArray(shape.toList()).toString())
                                    } else null)
                                val report=PhotoFiles.saveResult(this,file,bitmap,result,detector,"all",0.25f)
                                val data=JSONObject(report.readText()).put("validation_photo",name)
                                File(directory,"$prefix.json").writeText(data.toString(2))
                                reports.put(data)
                            }
                        } finally {bitmap.recycle()}
                    }
                }
                File(directory,"summary.json").writeText(JSONObject().put("status","complete").put("results",reports).toString(2))
                runOnUiThread {text.text="Проверка завершена: ${reports.length()} результатов"}
            } catch(error:Throwable) {
                File(directory,"summary.json").writeText(JSONObject().put("status","failed").put("error",error.stackTraceToString()).toString(2))
                runOnUiThread {text.text="Ошибка: ${error.message}"}
            }
        }.start()
    }
}

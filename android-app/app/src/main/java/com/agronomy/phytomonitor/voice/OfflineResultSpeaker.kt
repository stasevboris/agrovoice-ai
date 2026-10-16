package com.agronomy.phytomonitor.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import java.util.UUID

/** Select only an installed Russian voice which does not require a network. */
class OfflineResultSpeaker(context: Context) {
    private val main=Handler(Looper.getMainLooper())
    private var engine: TextToSpeech?=null
    var active by mutableStateOf(false);private set
    var available by mutableStateOf(false);private set
    var status by mutableStateOf("Подготовка озвучивания…");private set
    var initialized by mutableStateOf(false);private set
    var completion by mutableStateOf("idle");private set
    var voiceName by mutableStateOf("");private set
    private var closed=false
    private var utterance=""
    init {
        engine=TextToSpeech(context){code -> main.post {
            if(closed)return@post
            val tts=engine
            val voice=runCatching {
                if(code!=TextToSpeech.SUCCESS)null else tts?.voices?.filter {
                    it.locale.language=="ru" && !it.isNetworkConnectionRequired &&
                        !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }?.sortedBy {it.name}?.firstOrNull {tts.setVoice(it)==TextToSpeech.SUCCESS}
            }.getOrNull()
            initialized=true;available=voice!=null;voiceName=voice?.name ?: ""
            status=if(available)"Озвучивание без интернета доступно" else "Автономный русский голос недоступен. Результат доступен текстом."
        }}
        engine?.setOnUtteranceProgressListener(object: UtteranceProgressListener(){
            override fun onStart(id: String){main.post {if(id==utterance && !closed)status="Читаю результат…"}}
            override fun onDone(id: String){finish(id,null)}
            @Deprecated("Platform callback") override fun onError(id: String){finish(id,TextToSpeech.ERROR)}
            override fun onError(id: String,errorCode: Int){finish(id,errorCode)}
        })
    }
    private fun finish(id: String,error: Int?){main.post {if(id==utterance && !closed){
        active=false;completion=if(error==null)"done" else "error:$error"
        if(error in listOf(TextToSpeech.ERROR_NOT_INSTALLED_YET,TextToSpeech.ERROR_NETWORK,TextToSpeech.ERROR_NETWORK_TIMEOUT))available=false
        status=if(error==null)"Озвучивание без интернета доступно" else "Не удалось озвучить результат (код $error). Он доступен текстом."
    }}}
    fun speak(text: String,volume: Float=1f): Boolean {
        if(!available)return false
        utterance=UUID.randomUUID().toString();active=true;completion="pending"
        val parameters=Bundle().apply {putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume.coerceIn(0f,1f))}
        if(engine?.speak(text,TextToSpeech.QUEUE_FLUSH,parameters,utterance)!=TextToSpeech.SUCCESS){active=false;completion="error:queue";status="Не удалось начать озвучивание. Результат доступен текстом.";return false}
        return true
    }
    fun stop(){utterance="";engine?.stop();active=false;completion="stopped";if(available)status="Озвучивание без интернета доступно"}
    fun close(){closed=true;stop();engine?.shutdown();engine=null}
}

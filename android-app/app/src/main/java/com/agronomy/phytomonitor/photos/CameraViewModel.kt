package com.agronomy.phytomonitor.photos

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CameraState(val shotRequested: Boolean = false, val capturing: Boolean = false, val message: String? = null,
    val guidanceEnabled: Boolean = true, val qualitySamples: Int = 0,
    val qualityVerdict: com.agronomy.phytomonitor.PhotoVerdict? = null)

class CameraViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(CameraState())
    val state = mutableState.asStateFlow()
    fun open(automatic: Boolean) { mutableState.value = CameraState(shotRequested = automatic) }
    fun requestShot() = mutableState.update { if (it.capturing) it else it.copy(shotRequested = true) }
    fun consumeAutoShot(): Boolean {
        val requested = state.value.shotRequested
        mutableState.update { it.copy(shotRequested = false) }
        return requested
    }
    fun capturing(value: Boolean) = mutableState.update { it.copy(capturing = value) }
    fun error(message: String) = mutableState.update { it.copy(message = message) }
    fun guidance(enabled: Boolean) = mutableState.update { it.copy(guidanceEnabled = enabled) }
    fun quality(verdict: com.agronomy.phytomonitor.PhotoVerdict) = mutableState.update {
        if (it.guidanceEnabled) it.copy(qualitySamples = it.qualitySamples + 1, qualityVerdict = verdict) else it
    }
    fun close() { mutableState.value = CameraState() }
}

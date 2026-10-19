package com.agronomy.phytomonitor.photos

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CameraState(val shotRequested: Boolean = false, val capturing: Boolean = false, val message: String? = null)

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
    fun close() { mutableState.value = CameraState() }
}

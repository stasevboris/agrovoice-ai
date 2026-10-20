package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.photos.CameraViewModel
import org.junit.Assert.*
import org.junit.Test

class CameraReadinessTest {
    @Test fun previewReadinessIsClearedOnCloseAndNextOpen() {
        val model = CameraViewModel()
        model.open(false)
        assertFalse(model.state.value.previewStreaming)
        model.previewStreaming(true)
        assertTrue(model.state.value.previewStreaming)
        model.close()
        assertFalse(model.state.value.previewStreaming)
        model.open(true)
        assertFalse(model.state.value.previewStreaming)
        assertTrue(model.state.value.shotRequested)
    }
}

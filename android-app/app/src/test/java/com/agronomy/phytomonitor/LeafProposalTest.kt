package com.agronomy.phytomonitor

import com.agronomy.phytomonitor.ml.Letterbox
import com.agronomy.phytomonitor.ml.YoloPostprocessor
import org.junit.Assert.*
import org.junit.Test

class LeafProposalTest {
    @Test fun competingDiseaseClassesDoNotDuplicateOneLeafInTwoStageMode() {
        // Two heads describe the same physical leaf but disagree about its disease.
        val output=arrayOf(floatArrayOf(200f,200f),floatArrayOf(200f,200f),floatArrayOf(100f,100f),
            floatArrayOf(100f,100f),floatArrayOf(.9f,.1f),floatArrayOf(.1f,.8f))
        val geometry=Letterbox(416,416)
        assertEquals(2,YoloPostprocessor.decode(output,geometry).size)
        val leaves=YoloPostprocessor.decode(output,geometry,classAgnostic=true)
        assertEquals(1,leaves.size);assertEquals(.9f,leaves.single().confidence,1e-6f)
    }

    @Test fun paddingOnlyCandidatesAreNotClassifiedAsLeaves() {
        // In a wide photograph, the top of the padded square is outside the photo.
        val output=arrayOf(floatArrayOf(100f),floatArrayOf(10f),floatArrayOf(30f),floatArrayOf(10f),floatArrayOf(.9f))
        assertTrue(YoloPostprocessor.decode(output,Letterbox(2000,500),classAgnostic=true).isEmpty())
    }
}

package com.agronomy.phytomonitor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.ml.PhotoFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PhotoPipelineTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun allExifOrientationsHaveExpectedCorners() {
        val file=File(context.cacheDir,"orientation_test.jpg")
        val colors=intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        val bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val paint=Paint()
        colors.forEachIndexed { i,color ->
            paint.color=color
            val x=(i%2)*40f;val y=(i/2)*20f
            canvas.drawRect(x,y,x+40f,y+20f,paint)
        }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)) }
        bitmap.recycle()
        // Corner expectations from the EXIF specification: TL, TR, BL, BR.
        val expected=listOf(intArrayOf(0,1,2,3),intArrayOf(1,0,3,2),intArrayOf(3,2,1,0),
            intArrayOf(2,3,0,1),intArrayOf(0,2,1,3),intArrayOf(2,0,3,1),
            intArrayOf(3,1,2,0),intArrayOf(1,3,0,2))
        try {
            for(orientation in 1..8) {
                ExifInterface(file).apply {setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
                val decoded=PhotoFiles.decode(file)
                try {
                    assertEquals(if(orientation>=5)40 else 80,decoded.width)
                    assertEquals(if(orientation>=5)80 else 40,decoded.height)
                    expected[orientation-1].forEachIndexed { corner,colorIndex ->
                        val actual=decoded.getPixel(if(corner%2==0)decoded.width/4 else decoded.width*3/4,
                            if(corner/2==0)decoded.height/4 else decoded.height*3/4)
                        val wanted=colors[colorIndex]
                        val error=abs(Color.red(actual)-Color.red(wanted))+abs(Color.green(actual)-Color.green(wanted))+abs(Color.blue(actual)-Color.blue(wanted))
                        assertTrue("orientation=$orientation corner=$corner color error=$error",error<30)
                    }
                } finally {decoded.recycle()}
            }
        } finally {file.delete()}
    }

    @Test fun oversizedOddDimensionsStayWithinMemoryLimit() {
        val file=File(context.cacheDir,"odd_photo.png")
        val bitmap=Bitmap.createBitmap(4097,17,Bitmap.Config.ARGB_8888)
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        try {
            val decoded=PhotoFiles.decode(file)
            try {assertTrue(decoded.width<=2048);assertTrue(decoded.height>0)} finally {decoded.recycle()}
        } finally {file.delete()}
    }

    @Test fun brokenPhotoProducesReadableFailure() {
        val file=File(context.cacheDir,"broken_photo.jpg").apply {writeText("not an image")}
        try {
            try {PhotoFiles.decode(file);fail("Broken photo was accepted")}
            catch(error:IllegalArgumentException){assertTrue(error.message!!.contains("фото"))}
        } finally {file.delete()}
    }
}

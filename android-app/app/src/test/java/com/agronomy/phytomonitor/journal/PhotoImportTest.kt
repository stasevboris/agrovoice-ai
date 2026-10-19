package com.agronomy.phytomonitor.journal

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.SavedInspectionView
import com.agronomy.phytomonitor.actions.ActionPolicy
import com.agronomy.phytomonitor.actions.ActionRequest
import com.agronomy.phytomonitor.actions.ActionSource
import com.agronomy.phytomonitor.actions.AppState
import com.agronomy.phytomonitor.actions.Operation
import com.agronomy.phytomonitor.actions.Screen
import com.agronomy.phytomonitor.actions.Verdict
import com.agronomy.phytomonitor.ml.PhotoFiles
import com.agronomy.phytomonitor.voice.VoiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A-05: one copy per photo, and a saved inspection is viewed as saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun importingTheSamePhotoTwiceKeepsOneCopy() {
        val folder = PhotoFiles.folder(context)
        folder.deleteRecursively()
        val source = File(context.cacheDir, "picked.jpg").apply { writeBytes(ByteArray(5000) { (it % 7).toByte() }) }
        val first = PhotoFiles.import(context, Uri.fromFile(source))
        val second = PhotoFiles.import(context, Uri.fromFile(source))
        assertEquals(first, second)
        assertTrue(first.name.startsWith("import_"))
        assertTrue(first.readBytes().contentEquals(source.readBytes()))
        val other = File(context.cacheDir, "other.jpg").apply { writeBytes(ByteArray(5000) { 1 }) }
        assertNotEquals(first, PhotoFiles.import(context, Uri.fromFile(other)))
        assertEquals(2, PhotoFiles.folder(context).listFiles()!!.size)
    }

    @Test
    fun savedViewKeepsFramesAndLabelsTogetherStrongestFirst() {
        val parsed = JournalRecords.parse(JournalFixtures.legacyRecord("v", "/p.jpg"))
        val record = JournalRecords.toRecord(parsed, null, null, "/p.jpg")
        val weak = record.detections.single().copy(id = 2, labelRu = "Томат: здоров", confidence = 0.3, x1 = 1.0)
        val strong = record.detections.single().copy(id = 1, labelRu = "Томат: фитофтороз", confidence = 0.9, x1 = 50.0)
        val details = InspectionWithDetails(record.inspection, null, listOf(PhotoWithDetections(record.photo, listOf(weak, strong))))
        val view = SavedInspectionView.of(details, photo = null)
        assertEquals(listOf("Томат: фитофтороз", "Томат: здоров"), view.labels)
        assertEquals(50f, view.boxes[0].left, 0f)
        assertEquals(0.9f, view.boxes[0].confidence, 0.0001f)
    }

    @Test
    fun backFromASavedInspectionReturnsToTheJournal() {
        val back = ActionRequest(VoiceAction.BACK, source = ActionSource.VOICE)
        val open = AppState(screen = Screen.JOURNAL, inspectionOpen = true)
        assertEquals(listOf(Operation.CloseInspection), (ActionPolicy.resolve(back, open) as Verdict.Run).operations)
        assertEquals(listOf(Operation.CloseJournal), (ActionPolicy.resolve(back, open.copy(inspectionOpen = false)) as Verdict.Run).operations)
    }
}

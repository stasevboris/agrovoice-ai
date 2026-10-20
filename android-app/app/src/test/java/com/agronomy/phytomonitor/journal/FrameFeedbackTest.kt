package com.agronomy.phytomonitor.journal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FrameFeedbackTest {
    @Test
    fun archiveRejectsFeedbackClaimingAnotherKnownAnalysisVersionButKeepsUnknown() {
        val legacy = JournalFixtures.legacyRecord("versioned", "/missing.jpg")
        legacy.getJSONObject("prediction").put("result_provenance", JSONObject().put("schema_version", 1))
        val record = JournalRecords.toRecord(JournalRecords.parse(legacy), null, null, "/missing.jpg")
        val feedback = FrameFeedback(record.photo.id, record.photo.sha256, "", 2, "unknown", "", 100, 1)
        val details = InspectionWithDetails(record.inspection, null,
            listOf(PhotoWithDetections(record.photo, record.detections, feedback = feedback)))
        val data = JournalRecords.toJson(details)
        assertTrue(JournalRecords.parseObject(data, "fixture").isFailure)
        data.getJSONArray("frames").getJSONObject(0).getJSONObject("feedback").put("analysis_version", JSONObject.NULL)
        val parsed = JournalRecords.parseObject(data, "fixture").getOrThrow()
        assertNull(parsed.allFrames().single().feedback!!.analysisVersion)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun zipContainsOnlySelectedFrameAndRemovesExifWithRotatedCoordinatesPreserved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("feedback-source-", ".jpg", context.cacheDir)
        val exportedFile = File.createTempFile("feedback-output-", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setLatLong(53.9, 27.5)
                setAttribute(ExifInterface.TAG_ARTIST, "PRIVATE PERSON")
                saveAttributes()
            }
            val sha = InspectionJournal.sha256(file)
            val prediction = JournalFixtures.prediction(file.path, sha).put("decoded_width", 20).put("decoded_height", 30)
            val photo = PhotoEntity("chosen", "inspection", localPath = file.path, sha256 = sha, width = 20, height = 30,
                predictionJson = prediction.toString(), reportSha256 = "original-report")
            val output = ByteArrayOutputStream()
            FeedbackExport.write(listOf(PhotoWithDetections(photo, emptyList())), output) { file }
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            assertEquals(setOf("frames/1.png", "manifest.json"), entries.keys)
            exportedFile.writeBytes(entries.getValue("frames/1.png"))
            val exif = ExifInterface(exportedFile)
            assertNull(exif.latLong)
            assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
            val decoded = android.graphics.BitmapFactory.decodeFile(exportedFile.path)
            assertEquals(20, decoded.width)
            assertEquals(30, decoded.height)
            decoded.recycle()
            val frame = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)).getJSONArray("frames").getJSONObject(0)
            assertEquals(sha, frame.getString("original_photo_sha256"))
            assertEquals(InspectionJournal.sha256(exportedFile), frame.getString("export_photo_sha256"))
            assertNotEquals(sha, frame.getString("export_photo_sha256"))
            assertFalse(frame.toString().contains(file.path))
        } finally {
            file.delete()
            exportedFile.delete()
        }
    }

    @Test
    fun feedbackNeverChangesEvidenceAndRejectsStaleRevision() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val record = JournalRecords.toRecord(JournalRecords.parse(JournalFixtures.legacyRecord("feedback", "/missing.jpg")), null, null, "/missing.jpg")
            db.journal().saveRecord(record, SyncQueueEntity(entity = "inspection", entityId = "feedback", operation = "upsert", createdAt = 1))
            val before = db.journal().inspectionDetails("feedback")!!
            val journal = InspectionJournal(context, db) { 2_000_000_000_000L }
            journal.saveFrameFeedback(record.photo.id, 0, "incorrect", "Не знаю культуру")
            val after = journal.details("feedback")!!
            assertEquals(before.inspection.predictionJson, after.inspection.predictionJson)
            assertEquals(before.photos.single().photo, after.photos.single().photo)
            assertEquals(before.photos.single().detections, after.photos.single().detections)
            val feedback = after.photos.single().feedback!!
            assertEquals("user", feedback.source)
            assertEquals("", feedback.reportSha256)
            assertEquals(1, feedback.revision)
            assertTrue(runCatching { journal.saveFrameFeedback(record.photo.id, 0, "correct", "") }.isFailure)
            val restored = JournalRecords.parse(JournalRecords.toJson(after)).allFrames().single().feedback
            assertEquals(feedback, restored)
        } finally { db.close() }
    }

    @Test
    fun privateExportWhitelistsNestedFieldsAndKeepsOriginalAndExportHashesSeparate() {
        val raw = JournalFixtures.prediction("/private/user.jpg", "original")
            .put("note", "SECRET").put("lat", 53.0).put("agrovoice_sync_source", "SECRET")
            .put("result_provenance", JSONObject().put("model_sha256", "modelhash").put("account", "SECRET")
                .put("preprocessing", JSONObject().put("channels", "RGB").put("note", "SECRET")
                    .put("photo_decode", JSONObject().put("exif_orientation", "applied").put("GPS", "SECRET"))))
        raw.getJSONArray("detections").getJSONObject(0).put("note", "SECRET")
        val photo = PhotoEntity("frame", "inspection", localPath = "/private/user.jpg", sha256 = "original", width = 1536, height = 2048, predictionJson = raw.toString())
        val exported = FeedbackExport.manifestFrame(PhotoWithDetections(photo, emptyList()), "exported", "frames/1.png")
        val text = exported.toString()
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("/private"))
        assertFalse(text.contains("\"lat\""))
        assertEquals("original", exported.getString("original_photo_sha256"))
        assertEquals("exported", exported.getString("export_photo_sha256"))
        assertEquals("RGB", exported.getJSONObject("prediction").getJSONObject("result_provenance").getJSONObject("preprocessing").getString("channels"))
        assertEquals(4, exported.getJSONObject("prediction").getJSONArray("detections").getJSONObject(0).getJSONArray("xyxy_pixels").length())
    }
}

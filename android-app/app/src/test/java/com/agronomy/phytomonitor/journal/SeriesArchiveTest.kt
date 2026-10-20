package com.agronomy.phytomonitor.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<JournalDatabase>()

    private fun context(name: String): Context {
        val root = temporary.newFolder(name)
        return object : ContextWrapper(base) {
            override fun getExternalFilesDir(type: String?) = File(root, "files").apply { mkdirs() }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(base, JournalDatabase::class.java)
        .allowMainThreadQueries().build().also { databases += it }

    private fun photo(context: Context, name: String, bytes: ByteArray) =
        File(context.getExternalFilesDir(null), "photos/$name.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }

    private fun seedSeries(context: Context, db: JournalDatabase): InspectionJournal {
        val journal = InspectionJournal(context, db)
        val first = photo(context, "front", byteArrayOf(1, 2, 3))
        val report = File(context.getExternalFilesDir(null), "results/primary.json").apply {
            parentFile!!.mkdirs()
            writeText(JournalFixtures.prediction(first.path, InspectionJournal.sha256(first)).toString())
        }
        val saved = journal.save(report, "Серия", "uncertain", plot = "Участок", plant = "Растение")
        val primary = journal.details(saved.id)!!.photos.single().photo
        val second = photo(context, "back", byteArrayOf(4, 5, 6))
        db.journal().upsertPhoto(PhotoEntity("frame-back", saved.id, primary.plantId, second.path,
            InspectionJournal.sha256(second), 20, 10))
        return journal
    }

    @After
    fun close() = databases.forEach { it.close() }

    @Test
    fun everyFrameSurvivesArchiveToAnotherContextAndRepeatWithoutInventedAnalysis() {
        val source = seedSeries(context("source"), database())
        val original = source.details(source.list().single().id)!!
        val archive = ByteArrayOutputStream()
        assertEquals(2, source.exportArchive(archive))
        val target = InspectionJournal(context("target"), database())
        val imported = target.import(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(1, imported.added)
        assertEquals(0, imported.withoutPhoto)
        val restored = target.details(target.list().single().id)!!
        assertEquals(original.photos.map { it.photo.id }.toSet(), restored.photos.map { it.photo.id }.toSet())
        assertEquals(2, restored.photos.size)
        assertEquals(original.photos.map { it.photo.sha256 }.toSet(), restored.photos.map { InspectionJournal.sha256(File(it.photo.localPath)) }.toSet())
        assertTrue(restored.photos.all { it.plantName == "Растение" })
        assertTrue(restored.photos.single { it.photo.id == "frame-back" }.detections.isEmpty())
        assertEquals(1, target.import(ByteArrayInputStream(archive.toByteArray())).unchanged)
        assertEquals(2, target.details(target.list().single().id)!!.photos.size)
    }

    @Test
    fun missingMandatoryFrameFailsBeforeZipAndDoesNotAwardFullBackup() {
        val sourceContext = context("source")
        val db = database()
        val journal = seedSeries(sourceContext, db)
        val id = journal.list().single().id
        val absent = journal.details(id)!!.photos.single { it.photo.id == "frame-back" }.photo
        db.journal().upsertPhoto(absent.copy(localPath = File(sourceContext.filesDir, "absent.jpg").path))
        val output = ByteArrayOutputStream()
        val error = runCatching { journal.exportArchive(output) }.exceptionOrNull()
        assertTrue("Incomplete archive must fail explicitly", error is IOException)
        assertEquals(0, output.size())
        assertEquals(0, db.journal().eventCount(JournalEventEntity.FULL_BACKUP))
        assertEquals(2, journal.details(id)!!.photos.size)
    }

    @Test
    fun changedFrameFailsBeforeZipWithoutChangingJournalOrFiles() {
        val sourceContext = context("source")
        val db = database()
        val journal = seedSeries(sourceContext, db)
        val id = journal.list().single().id
        val frame = journal.details(id)!!.photos.single { it.photo.id == "frame-back" }.photo
        val before = journal.record(id).toString()
        File(frame.localPath).writeBytes(byteArrayOf(99))
        val output = ByteArrayOutputStream()
        assertTrue(runCatching { journal.exportArchive(output) }.exceptionOrNull() is IOException)
        assertEquals(0, output.size())
        assertEquals(before, journal.record(id).toString())
        assertTrue(File(frame.localPath).readBytes().contentEquals(byteArrayOf(99)))
    }

    @Test
    fun savedExtraDetectionsWithoutAFullReportArePreservedWithoutInventingAnalysis() {
        val sourceDb = database()
        val source = seedSeries(context("source"), sourceDb)
        sourceDb.journal().insertDetections(listOf(DetectionEntity(photoId = "frame-back", classCode = "source-label",
            labelRu = "Сохранённая метка без отчёта", confidence = 0.4, x1 = 1.0, y1 = 2.0, x2 = 3.0, y2 = 4.0)))
        val bytes = ByteArrayOutputStream().also { source.exportArchive(it) }.toByteArray()
        val target = InspectionJournal(context("target"), database())
        assertEquals(1, target.import(ByteArrayInputStream(bytes)).added)
        val restored = target.details(target.list().single().id)!!.photos.single { it.photo.id == "frame-back" }
        assertNull(restored.photo.predictionJson)
        assertEquals("Сохранённая метка без отчёта", restored.detections.single().labelRu)
        assertEquals(0.4, restored.detections.single().confidence, 0.0)
    }

    @Test
    fun blankIncomingPhotoHashCannotRebindExistingAnalysisToDifferentBytes() {
        val sourceContext = context("source")
        val journal = seedSeries(sourceContext, database())
        val original = journal.details(journal.list().single().id)!!
        val document = newerDocument(journal)
        val primary = document.getJSONArray("records").getJSONObject(0).getJSONArray("frames").getJSONObject(0)
        primary.put("photo_sha256", "").put("archive_photo", "photos/replacement.jpg")
        val replacement = photo(sourceContext, "replacement", byteArrayOf(8, 9, 10))
        val archive = ByteArrayOutputStream()
        JournalArchive.write(archive, document.toString(), mapOf("photos/replacement.jpg" to replacement))

        val result = journal.import(ByteArrayInputStream(archive.toByteArray()))

        assertEquals("The replacement must be rejected", 0, result.updated)
        assertTrue(result.issues.isNotEmpty())
        assertEquals(original, journal.details(original.inspection.id))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(original.photos.first().photo.localPath).readBytes())
    }

    @Test
    fun missingIncomingReportHashCannotEraseKnownFrameHash() {
        val journal = seedSeries(context("source"), database())
        val original = journal.details(journal.list().single().id)!!
        val document = newerDocument(journal)
        document.getJSONArray("records").getJSONObject(0).getJSONArray("frames")
            .getJSONObject(0).remove("report_sha256")

        val result = importJson(journal, document)

        assertEquals(0, result.updated)
        assertTrue(result.issues.isNotEmpty())
        assertEquals(original, journal.details(original.inspection.id))
    }

    @Test
    fun newerImportCannotReplaceTopLevelEvidenceWhileFramesStayUnchanged() {
        val journal = seedSeries(context("source"), database())
        val original = journal.details(journal.list().single().id)!!
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("prediction").put("model", "another-model") },
            { it.getJSONObject("prediction").put("model_sha256", "f".repeat(64)) },
            { it.getJSONObject("prediction").put("detections", JSONArray()) },
            { it.put("report_name", "another-report.json") },
            { it.put("report_sha256", "") }
        )
        for (edit in edits) {
            val document = newerDocument(journal)
            edit(document.getJSONArray("records").getJSONObject(0))
            val result = importJson(journal, document)
            assertEquals("Original inspection evidence must survive", 0, result.updated)
            assertTrue(result.issues.isNotEmpty())
            assertEquals(original, journal.details(original.inspection.id))
        }
    }

    @Test
    fun freshImportRejectsDifferentPrimaryAndInspectionAnalysis() {
        val source = seedSeries(context("source"), database())
        val document = JSONObject(source.export())
        document.getJSONArray("records").getJSONObject(0).getJSONObject("prediction")
            .put("model", "unrelated-model")
        val target = InspectionJournal(context("target"), database())

        val result = importJson(target, document)

        assertEquals(0, result.added)
        assertTrue(result.issues.isNotEmpty())
        assertTrue(target.list().isEmpty())
    }

    @Test
    fun importingAnalysisForLegacyDetectionsCannotLoseOriginalBoxesInNextBackup() {
        val db = database()
        val journal = seedSeries(context("source"), db)
        db.journal().insertDetections(listOf(DetectionEntity(photoId = "frame-back", classCode = "original",
            labelRu = "Старая рамка", confidence = 0.4, x1 = 1.0, y1 = 2.0, x2 = 3.0, y2 = 4.0)))
        val id = journal.list().single().id
        val document = newerDocument(journal)
        val frames = document.getJSONArray("records").getJSONObject(0).getJSONArray("frames")
        val extra = (0 until frames.length()).map { frames.getJSONObject(it) }.single { it.getString("id") == "frame-back" }
        extra.remove("stored_detections")
        extra.put("prediction_json", JournalFixtures.prediction(extra.getString("photo_path"),
            extra.getString("photo_sha256"), emptyList()).toString())

        val result = importJson(journal, document)
        val bytes = ByteArrayOutputStream().also { journal.exportArchive(it) }.toByteArray()
        val restored = InspectionJournal(context("restored"), database())
        assertEquals(1, restored.import(ByteArrayInputStream(bytes)).added)
        val roundtrip = restored.details(id)!!.photos.single { it.photo.id == "frame-back" }

        assertEquals("Original stored detection must survive a backup roundtrip", "original", roundtrip.detections.singleOrNull()?.classCode)
        assertNull(roundtrip.photo.predictionJson)
        assertEquals(0, result.updated)
        assertTrue(result.issues.isNotEmpty())
    }

    @Test
    fun savingAnalysisForLegacyDetectionsRequiresNewFrameIdentity() {
        val sourceContext = context("source")
        val db = database()
        val journal = seedSeries(sourceContext, db)
        db.journal().insertDetections(listOf(DetectionEntity(photoId = "frame-back", classCode = "original",
            labelRu = "Старая рамка", confidence = 0.4, x1 = 1.0, y1 = 2.0, x2 = 3.0, y2 = 4.0)))
        val original = journal.details(journal.list().single().id)!!
        val extra = original.photos.single { it.photo.id == "frame-back" }.photo
        val report = File(sourceContext.filesDir, "results/new-back.json").apply {
            writeText(JournalFixtures.prediction(extra.localPath, extra.sha256, emptyList()).toString())
        }
        val primary = original.photos.first().photo
        val attempt = runCatching {
            journal.saveSeries(original.inspection.id, listOf(
                InspectionFrame(primary.id, File(sourceContext.filesDir, "results/primary.json")),
                InspectionFrame(extra.id, report)
            ), "Правка", "uncertain", "Участок", plant = "Растение")
        }

        assertTrue("An old observation must not acquire unrelated analysis", attempt.isFailure)
        assertEquals(original, journal.details(original.inspection.id))
    }

    @Test
    fun newerMetadataImportKeepsAnalysisAndAllowsNoteAndAngleChanges() {
        val journal = seedSeries(context("source"), database())
        val original = journal.details(journal.list().single().id)!!
        val document = newerDocument(journal)
        val record = document.getJSONArray("records").getJSONObject(0)
        record.put("note", "Уточнённая заметка")
        record.getJSONArray("frames").getJSONObject(0).put("role", "front")

        assertEquals(1, importJson(journal, document).updated)
        val changed = journal.details(original.inspection.id)!!
        assertEquals("Уточнённая заметка", changed.inspection.note)
        assertEquals("front", changed.photos.first().photo.frameRole)
        assertEquals(original.inspection.predictionJson, changed.inspection.predictionJson)
        assertEquals(original.photos.map { it.detections }, changed.photos.map { it.detections })
    }

    private fun newerDocument(journal: InspectionJournal): JSONObject = JSONObject(journal.export()).apply {
        val record = getJSONArray("records").getJSONObject(0)
        record.put("updated_epoch_ms", record.getLong("updated_epoch_ms") + 1)
    }

    private fun importJson(journal: InspectionJournal, document: JSONObject) =
        journal.import(ByteArrayInputStream(document.toString().toByteArray(Charsets.UTF_8)))
}

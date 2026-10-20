package com.agronomy.phytomonitor.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesSaveTest {
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

    private fun frame(context: Context, name: String, role: FrameRole): InspectionFrame {
        val photo = File(context.filesDir, "photos/$name.jpg").apply {
            parentFile!!.mkdirs()
            writeText("synthetic photo $name")
        }
        val report = File(context.filesDir, "results/$name.json").apply {
            parentFile!!.mkdirs()
            writeText(JournalFixtures.prediction(photo.path, InspectionJournal.sha256(photo), listOf("Fixture $name"))
                .put("model", "fixture-$name").put("model_sha256", "a".repeat(64)).toString(2))
        }
        return InspectionFrame("id-$name", report, role)
    }

    @After
    fun close() = databases.forEach { it.close() }

    @Test
    fun saveRepeatAppendAndArchivePreserveEveryOwnResultAndPlantLink() {
        val sourceContext = context("source")
        val db = database()
        val journal = InspectionJournal(sourceContext, db)
        val first = frame(sourceContext, "first", FrameRole.FRONT)
        val second = frame(sourceContext, "second", FrameRole.BACK)
        val inputs = listOf(first, second)
        val saved = journal.saveSeries("inspection", inputs, "Заметка", "uncertain", "Участок", plant = "Растение")
        val before = journal.details(saved.id)!!
        val pending = db.journal().pendingSync().size
        assertEquals(saved.id, journal.saveSeries("inspection", inputs, "Заметка", "uncertain", "Участок", plant = "Растение").id)
        assertEquals(pending, db.journal().pendingSync().size)
        assertEquals(before, journal.details(saved.id))
        val third = frame(sourceContext, "third", FrameRole.OVERVIEW)
        journal.saveSeries(saved.id, inputs + third, "Заметка", "uncertain", "Участок", plant = "Растение")
        val added = journal.details(saved.id)!!
        assertEquals(listOf("id-first", "id-second", "id-third"), added.photos.map { it.photo.id })
        assertEquals(listOf("front", "back", "overview"), added.photos.map { it.photo.frameRole })
        assertEquals(first.report.readText(), added.photos[0].photo.predictionJson)
        assertEquals(second.report.readText(), added.photos[1].photo.predictionJson)
        assertEquals(before.inspection.predictionJson, added.inspection.predictionJson)
        assertEquals(before.inspection.reportName, added.inspection.reportName)
        val archive = ByteArrayOutputStream()
        assertEquals(3, journal.exportArchive(archive))
        val restored = InspectionJournal(context("target"), database())
        assertEquals(1, restored.import(ByteArrayInputStream(archive.toByteArray())).added)
        val roundTrip = restored.details(saved.id)!!
        assertEquals(added.photos.map { it.photo.id }, roundTrip.photos.map { it.photo.id })
        assertEquals(added.photos.map { it.photo.predictionJson }, roundTrip.photos.map { it.photo.predictionJson })
        assertEquals(added.photos.map { it.photo.frameRole }, roundTrip.photos.map { it.photo.frameRole })
        assertEquals(added.photos.map { it.photo.plantId }, roundTrip.photos.map { it.photo.plantId })
        assertEquals(added.photos.map { it.detections.single().labelRu }, roundTrip.photos.map { it.detections.single().labelRu })
        assertEquals(1, restored.import(ByteArrayInputStream(archive.toByteArray())).unchanged)
        assertEquals(3, restored.details(saved.id)!!.photos.size)
    }

    @Test
    fun recoveredPrimaryReportUsesExistingIdentityAndNewFrameDoesNotOverwriteIt() {
        val source = context("source")
        val journal = InspectionJournal(source, database())
        val first = frame(source, "first", FrameRole.FRONT)
        val existing = journal.save(first.report, "Первый", "unrated")
        val originalId = journal.details(existing.id)!!.photos.single().photo.id
        val second = frame(source, "second", FrameRole.BACK)
        val resumed = journal.saveSeries("different-draft-id", listOf(first, second), "Серия", "unrated")
        assertEquals(existing.id, resumed.id)
        assertEquals(originalId, journal.details(resumed.id)!!.photos.first().photo.id)
        assertEquals(1, journal.list().size)
        assertEquals(2, journal.details(resumed.id)!!.photos.size)
    }

    @Test
    fun changedEvidenceOrReusedForeignIdCannotOverwriteSavedFrame() {
        val source = context("source")
        val db = database()
        val journal = InspectionJournal(source, db)
        val first = frame(source, "first", FrameRole.FRONT)
        journal.saveSeries("inspection", listOf(first), "Заметка", "unrated")
        val before = journal.details("inspection")!!
        first.report.writeText(JSONObject(first.report.readText()).put("model", "changed-model").toString())
        assertTrue(runCatching { journal.saveSeries("inspection", listOf(first), "Правка", "unrated") }.isFailure)
        assertEquals(before, journal.details("inspection"))
        val other = frame(source, "other", FrameRole.BACK).copy(id = first.id)
        assertTrue(runCatching { journal.saveSeries("other-inspection", listOf(other), "Другой", "unrated") }.isFailure)
        assertEquals(before, journal.details("inspection"))
        assertEquals(1, journal.list().size)
    }

    @Test
    fun interruptedRestoreRollsBackRecordsAndRetryReusesCopiedFrames() {
        val sourceContext = context("source")
        val source = InspectionJournal(sourceContext, database())
        val first = frame(sourceContext, "first", FrameRole.FRONT)
        val second = frame(sourceContext, "second", FrameRole.BACK)
        source.saveSeries("inspection", listOf(first, second), "Заметка", "unrated")
        val bytes = ByteArrayOutputStream().also { source.exportArchive(it) }.toByteArray()
        val targetContext = context("target")
        val targetDb = database()
        var checks = 0
        val constrained = InspectionJournal(targetContext, targetDb, availableSpace = { directory ->
            if (directory.name == "photos" && ++checks >= 3) 0L else Long.MAX_VALUE
        })
        assertTrue(runCatching { constrained.import(ByteArrayInputStream(bytes)) }.isFailure)
        assertTrue(constrained.list().isEmpty())
        assertTrue(targetDb.journal().pendingSync().isEmpty())
        val recovered = InspectionJournal(targetContext, targetDb)
        assertEquals(1, recovered.import(ByteArrayInputStream(bytes)).added)
        assertEquals(2, recovered.details("inspection")!!.photos.size)
        assertEquals(1, recovered.import(ByteArrayInputStream(bytes)).unchanged)
        assertEquals(2, File(targetContext.filesDir, "photos").listFiles()!!.size)
    }

    @Test
    fun addingEvidenceRequiresNewExpertReview() {
        val source = context("source")
        val db = database()
        val journal = InspectionJournal(source, db)
        val first = frame(source, "first", FrameRole.FRONT)
        journal.saveSeries("inspection", listOf(first), "", "unrated")
        val original = journal.details("inspection")!!.inspection
        db.journal().upsertInspection(original.copy(expertStatus = "confirmed"))
        val second = frame(source, "second", FrameRole.BACK)
        journal.saveSeries("inspection", listOf(first, second), "", "unrated")
        assertEquals("unreviewed", journal.details("inspection")!!.inspection.expertStatus)
        assertEquals(original.predictionJson, journal.details("inspection")!!.inspection.predictionJson)
    }
}

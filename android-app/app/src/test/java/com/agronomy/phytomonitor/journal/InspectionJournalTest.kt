package com.agronomy.phytomonitor.journal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Room DAO, migration of the old JSON journal and restore from a portable archive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InspectionJournalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    private val databases = mutableListOf<JournalDatabase>()
    private var now = 1_770_000_000_000L

    private fun database(): JournalDatabase = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
        .allowMainThreadQueries()
        .build()
        .also { databases += it }

    private fun journal(database: JournalDatabase = database()) = InspectionJournal(context, database) { now }

    private fun photo(name: String, bytes: ByteArray = byteArrayOf(7, 7, 7)): File =
        File(files, "photos/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }

    private fun legacy(name: String, text: String) {
        File(files, "inspections").mkdirs()
        File(files, "inspections/$name").writeText(text)
    }

    @Before
    fun clean() {
        files.deleteRecursively()
        files.mkdirs()
    }

    @After
    fun close() {
        databases.forEach { it.close() }
    }

    @Test
    fun editingSavedNotePreservesPhotosReportAndOtherRecordsAndSurvivesExport() {
        val picture = photo("note.jpg")
        legacy("note.json", JournalFixtures.legacyRecord("note", picture.path, InspectionJournal.sha256(picture)).toString())
        legacy("other.json", JournalFixtures.legacyRecord("other", "/missing.jpg").toString())
        val db = database()
        val journal = journal(db)
        journal.migrateLegacy()
        val before = journal.details("note")!!
        val other = journal.record("other").toString()
        val queued = db.journal().pendingSync().size
        val bytes = picture.readBytes()
        val edited = journal.editNote("note", before.inspection.updatedAt, "Исправленная заметка")
        val after = journal.details("note")!!
        assertEquals(before.copy(inspection = before.inspection.copy(note = edited.note, updatedAt = edited.updatedAt)), after)
        assertEquals(other, journal.record("other").toString())
        assertTrue(bytes.contentEquals(picture.readBytes()))
        assertEquals(queued + 1, db.journal().pendingSync().size)
        journal.editNote("note", edited.updatedAt, edited.note)
        assertEquals(queued + 1, db.journal().pendingSync().size)
        val restored = journal()
        restored.import(ByteArrayInputStream(journal.export().toByteArray(Charsets.UTF_8)))
        assertEquals(edited.note, restored.details("note")!!.inspection.note)
    }

    @Test
    fun staleMissingAndOversizedNoteEditsLeaveTheRecordUnchanged() {
        legacy("note.json", JournalFixtures.legacyRecord("note", "/missing.jpg").toString())
        val journal = journal()
        journal.migrateLegacy()
        val original = journal.details("note")!!.inspection
        now = original.updatedAt - 100
        val updated = journal.editNote("note", original.updatedAt, "Новая версия")
        assertEquals(original.updatedAt + 1, updated.updatedAt)
        assertTrue(runCatching { journal.editNote("note", original.updatedAt, "Старая версия") }.isFailure)
        assertTrue(runCatching { journal.editNote("absent", updated.updatedAt, "Текст") }.isFailure)
        assertTrue(runCatching { journal.editNote("note", updated.updatedAt, "а".repeat(MAX_NOTE + 1)) }.isFailure)
        assertEquals(updated, journal.details("note")!!.inspection)
    }

    @Test
    fun oldJournalMovesOnceAndBrokenFilesAreListed() {
        val picture = photo("import_1.jpg")
        val sha = InspectionJournal.sha256(picture)
        legacy("a.json", JournalFixtures.legacyRecord("a", picture.path, sha).toString(2))
        legacy("b.json", JournalFixtures.legacyRecord("b", "/gone/import_2.jpg", note = "Второй").toString(2))
        legacy("c.json", "{обрыв")
        legacy("d.json", JournalFixtures.legacyRecord("d", picture.path).put("review", "плохо").toString())
        legacy("e.tmp", "{}")
        val journal = journal()

        val report = journal.migrateLegacy()!!
        assertEquals(2, report.added)
        assertEquals(1, report.withoutPhoto)
        assertEquals(listOf("c.json", "d.json", "e.tmp"), report.issues.map { it.source }.sorted())
        assertNull(journal.migrateLegacy())
        assertEquals(3, journal.migrationReport()!!.issues.size)

        val entries = journal.list()
        assertEquals(setOf("a", "b"), entries.map { it.id }.toSet())
        val first = entries.first { it.id == "a" }
        assertEquals("Теплица 2", first.plot)
        assertEquals(1, first.detections)
        assertTrue(first.photoAvailable)
        assertFalse(entries.first { it.id == "b" }.photoAvailable)
        assertTrue(File(files, "inspections/c.json").isFile)
        assertTrue(File(files, "inspections/a.json").isFile)
    }

    @Test
    fun savingTheSameReportUpdatesOneInspection() {
        val database = database()
        val journal = journal(database)
        val picture = photo("camera_1.jpg")
        val report = File(files, "results/1770_yolo26n_x.json").apply {
            parentFile!!.mkdirs()
            writeText(JournalFixtures.prediction(picture.path, InspectionJournal.sha256(picture), listOf("Томат: здоров", "Томат: септориоз")).toString())
        }
        val first = journal.save(report, "Первая заметка", "unrated", "Участок 1")
        now += 1000
        val second = journal.save(report, "Исправленная заметка", "correct", "Участок 1")

        assertEquals(first.id, second.id)
        assertEquals(1, database.journal().inspectionCount())
        val details = database.journal().inspectionDetails(first.id)!!
        assertEquals("Исправленная заметка", details.inspection.note)
        assertEquals(first.created, details.inspection.createdAt)
        assertEquals(now, details.inspection.updatedAt)
        assertEquals(2, details.photos.single().detections.size)
        assertEquals(2, database.journal().pendingSync().size)
        assertEquals("Участок 1", details.plot!!.name)
    }

    @Test
    fun archiveRestoresRecordsAndPhotosOnAnEmptyPhone() {
        val picture = photo("import_9.jpg", byteArrayOf(1, 2, 3, 4, 5))
        val sha = InspectionJournal.sha256(picture)
        legacy("r.json", JournalFixtures.legacyRecord("r", picture.path, sha).toString())
        val source = journal()
        source.migrateLegacy()
        val archive = ByteArrayOutputStream()
        assertEquals(1, source.exportArchive(archive))

        files.deleteRecursively()
        val target = journal()
        val report = target.import(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(1, report.added)
        assertEquals(0, report.withoutPhoto)
        assertTrue(report.issues.isEmpty())
        val restored = target.list().single()
        assertTrue(restored.photoAvailable)
        assertTrue(restored.photo.endsWith("restored_r_${sha}.jpg"))
        assertEquals(sha, InspectionJournal.sha256(File(restored.photo)))
        assertEquals("Нижние листья", restored.note)

        val again = target.import(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(1, again.unchanged)
        assertEquals(1, target.list().size)
    }

    @Test
    fun lackOfSpaceDuringPhotoRestoreRollsBackAllRecordsAndRetryRecovers() {
        val first = photo("archive_a.jpg", byteArrayOf(1, 2, 3))
        val second = photo("archive_b.jpg", byteArrayOf(4, 5, 6))
        val records = org.json.JSONArray()
        for ((id, picture) in listOf("a" to first, "b" to second)) {
            records.put(JournalFixtures.legacyRecord(id, "/absent/$id.jpg", InspectionJournal.sha256(picture))
                .put("archive_photo", "photos/$id.jpg"))
        }
        val bytes = ByteArrayOutputStream()
        JournalArchive.write(bytes, JournalRecords.document(records, true, now).toString(),
            mapOf("photos/a.jpg" to first, "photos/b.jpg" to second))
        val db = database()
        var checks = 0
        val constrained = InspectionJournal(context, db, availableSpace = { directory ->
            if (directory.name == "photos" && ++checks >= 3) 0L else Long.MAX_VALUE
        }) { now }
        assertTrue(runCatching { constrained.import(ByteArrayInputStream(bytes.toByteArray())) }.isFailure)
        assertTrue(constrained.list().isEmpty())
        assertTrue(db.journal().pendingSync().isEmpty())
        assertTrue(db.journal().plotsWithCounts().isEmpty())
        assertTrue(File(files, "photos").listFiles().orEmpty().none { it.extension == "part" })
        assertTrue(first.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        val recovered = journal(db)
        assertEquals(2, recovered.import(ByteArrayInputStream(bytes.toByteArray())).added)
        assertTrue(recovered.list().all { it.photoAvailable })
        assertEquals(2, recovered.import(ByteArrayInputStream(bytes.toByteArray())).unchanged)
    }

    @Test
    fun insufficientStagingSpaceLeavesExistingJournalUntouched() {
        val db = database()
        val source = journal(db)
        val picture = photo("existing.jpg")
        legacy("old.json", JournalFixtures.legacyRecord("old", picture.path).toString())
        source.migrateLegacy()
        val before = source.record("old").toString()
        val archive = ByteArrayOutputStream()
        source.exportArchive(archive)
        val constrained = InspectionJournal(context, db, availableSpace = { 0L }) { now }
        val report = constrained.import(ByteArrayInputStream(archive.toByteArray()))
        assertTrue(report.issues.any { it.reason.contains("свободного места") })
        assertEquals(before, source.record("old").toString())
    }

    @Test
    fun photoWithAnotherHashIsNotRestored() {
        val picture = photo("import_3.jpg")
        legacy("t.json", JournalFixtures.legacyRecord("t", picture.path, "0".repeat(64)).toString())
        val source = journal()
        source.migrateLegacy()
        val archive = ByteArrayOutputStream()
        source.exportArchive(archive)

        files.deleteRecursively()
        val target = journal()
        val report = target.import(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(1, report.added)
        assertEquals(1, report.withoutPhoto)
        assertTrue(report.issues.single().reason.contains("SHA-256"))
        assertFalse(target.list().single().photoAvailable)
    }

    @Test
    fun jsonImportKeepsTheNewerLocalVersion() {
        val database = database()
        val journal = journal(database)
        legacy("n.json", JournalFixtures.legacyRecord("n", "/p.jpg", note = "Новая", updated = 2_000L).toString())
        journal.migrateLegacy()
        fun document(note: String, updated: Long) = JournalRecords.document(
            org.json.JSONArray().put(JournalFixtures.legacyRecord("n", "/p.jpg", note = note, updated = updated)),
            containsPhotos = false,
            exportedAt = 3_000L
        ).toString()
        val older = document("Старая", 1_000L)

        val report = journal.import(ByteArrayInputStream(older.toByteArray()))
        assertEquals(1, report.unchanged)
        assertEquals("Новая", journal.list().single().note)

        val newer = document("Самая новая", 9_000L)
        val updated = journal.import(ByteArrayInputStream(newer.toByteArray()))
        assertEquals(1, updated.updated)
        assertEquals("Самая новая", journal.list().single().note)
    }

    @Test
    fun damagedImportChangesNothingAndExplainsWhy() {
        val journal = journal()
        val report = journal.import(ByteArrayInputStream("PK\u0003\u0004 обрезанный архив".toByteArray()))
        assertTrue(report.issues.isNotEmpty())
        val json = journal.import(ByteArrayInputStream("{\"schema\":\"чужой\"}".toByteArray()))
        assertTrue(json.issues.single().reason.contains("неизвестный формат"))
        assertTrue(journal.list().isEmpty())
        val records = org.json.JSONArray().put(JournalFixtures.legacyRecord("cut", "/absent.jpg"))
        val bytes = ByteArrayOutputStream()
        JournalArchive.write(bytes, JournalRecords.document(records, true, now).toString(), emptyMap())
        val missingFooter = bytes.toByteArray().copyOf(bytes.size() - 22)
        assertTrue(journal.import(ByteArrayInputStream(missingFooter)).issues.isNotEmpty())
        assertTrue(journal.list().isEmpty())
    }

    @Test
    fun deletingAnInspectionRemovesItsPhotoAndDetections() {
        val database = database()
        val journal = journal(database)
        legacy("z.json", JournalFixtures.legacyRecord("z", "/p.jpg").toString())
        journal.migrateLegacy()
        journal.deleteForTest("z")
        assertEquals(0, database.journal().inspectionCount())
        assertTrue(database.journal().pendingSync().none { it.entityId == "z" })
        assertNull(database.journal().inspectionDetails("z"))
        val photos = database.query("SELECT COUNT(*) FROM photo", null).use { it.moveToFirst(); it.getInt(0) }
        val detections = database.query("SELECT COUNT(*) FROM detection", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, photos)
        assertEquals(0, detections)
    }

    @Test
    fun csvAndJsonExportsContainTheJournal() {
        val journal = journal()
        legacy("x.json", JournalFixtures.legacyRecord("x", "/p.jpg").toString())
        journal.migrateLegacy()
        val document = JSONObject(journal.export())
        assertEquals(JOURNAL_SCHEMA, document.getString("schema"))
        assertFalse(document.getBoolean("contains_photo_bytes"))
        assertEquals("x", document.getJSONArray("records").getJSONObject(0).getString("id"))
        val csv = journal.exportCsv()
        assertTrue(csv.contains("x;"))
        assertTrue(csv.contains("Томат: фитофтороз"))
    }
}

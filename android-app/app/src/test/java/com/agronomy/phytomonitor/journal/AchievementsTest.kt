package com.agronomy.phytomonitor.journal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Local badges: unique records earn once, import and repeats cannot duplicate awards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AchievementsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    private val databases = mutableListOf<JournalDatabase>()
    private var now = 1_770_000_000_000L

    private fun database(): JournalDatabase = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
        .allowMainThreadQueries()
        .build()
        .also { databases += it }

    private fun journal(database: JournalDatabase) = InspectionJournal(context, database) { now }

    private fun photo(name: String): File = File(files, "photos/$name").apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(5, 5, 5))
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

    /** Writes a legacy file; the migration marker is global, so a test migrates exactly once. */
    private fun legacyFile(id: String, picture: File, plot: String = "", note: String = "") {
        File(files, "inspections").mkdirs()
        val record = JournalFixtures.legacyRecord(id, picture.path, InspectionJournal.sha256(picture),
            plot = plot, note = note)
        File(files, "inspections/$id.json").writeText(record.toString())
    }

    @Test
    fun importOfTheSameRecordsCannotAwardTwice() {
        val db = database()
        legacyFile("a", photo("a.jpg"))
        journal(db).migrateLegacy()
        assertEquals(listOf(AchievementCatalog.FIRST_INSPECTION), journal(db).achievements().map { it.id })
        val archive = ByteArrayOutputStream()
        journal(db).exportArchive(archive)
        val reimport = journal(db).import(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(0, reimport.added)
        // first_inspection and backup_created stay single rows despite the repeated facts.
        assertEquals(2, journal(db).achievements().size)
        assertEquals(1, journal(db).achievements().count { it.id == AchievementCatalog.FIRST_INSPECTION })
    }

    @Test
    fun completeRecordNeedsPlotPhotoAndNote() {
        val db = database()
        legacyFile("plain", photo("plain.jpg"))
        legacyFile("full", photo("full.jpg"), plot = "Поле 1", note = "Нижние листья")
        journal(db).migrateLegacy()
        val earned = journal(db).achievements().map { it.id }
        assertTrue(AchievementCatalog.COMPLETE_RECORD in earned)
        assertTrue(AchievementCatalog.FIRST_INSPECTION in earned)
    }

    @Test
    fun fullArchiveAwardsBackupButJsonExportAndImportDoNot() {
        val db = database()
        legacyFile("kept", photo("kept.jpg"))
        journal(db).migrateLegacy()
        val reporter = journal(db)
        assertTrue(reporter.achievements().none { it.id == AchievementCatalog.BACKUP_CREATED })
        ByteArrayOutputStream().also { reporter.exportArchive(it) }
        assertTrue(reporter.achievements().any { it.id == AchievementCatalog.BACKUP_CREATED })

        val archive = ByteArrayOutputStream()
        reporter.exportArchive(archive)
        val otherDb = database()
        val restored = journal(otherDb)
        restored.import(ByteArrayInputStream(archive.toByteArray()))
        val earned = restored.achievements().map { it.id }
        // The restored journal re-earns record badges from its own facts, but the backup award is
        // this device's event and is not exported.
        assertTrue(AchievementCatalog.FIRST_INSPECTION in earned)
        assertTrue(AchievementCatalog.BACKUP_CREATED !in earned)
    }

    @Test
    fun disabledAchievementsWriteNoRowsAndReenableKeepsHistory() {
        val db = database()
        val silent = journal(db)
        silent.achievementsEnabled = false
        File(files, "inspections").mkdirs()
        File(files, "inspections/quiet.json").writeText(
            JournalFixtures.legacyRecord("quiet", photo("quiet.jpg").path,
                InspectionJournal.sha256(photo("quiet.jpg"))).toString())
        silent.migrateLegacy()
        assertTrue(db.journal().achievements().isEmpty())

        val later = journal(db)
        File(files, "inspections2").mkdirs()
        val report = ByteArrayOutputStream()
        later.exportArchive(report)
        // Re-enabling does not retroactively fake events, but the next recomputation earns from facts.
        val earned = later.achievements().map { it.id }
        assertTrue(AchievementCatalog.FIRST_INSPECTION in earned)
        assertTrue(AchievementCatalog.BACKUP_CREATED in earned)
    }

    @Test
    fun geoMarkedShotNeedsPhotoAndCoordinates() {
        val db = database()
        File(files, "inspections").mkdirs()
        val picture = photo("geo.jpg")
        val plain = JournalFixtures.legacyRecord("geo", picture.path, InspectionJournal.sha256(picture),
            note = "", plot = "")
        plain.put("location", org.json.JSONObject().put("lat", 52.1).put("lon", 23.7).put("accuracy_m", 12.0))
        File(files, "inspections/geo.json").writeText(plain.toString())
        journal(db).migrateLegacy()
        val earned = journal(db).achievements().map { it.id }
        // Photo and coordinates earn the badge; the empty note and plot keep complete_record locked.
        assertTrue(AchievementCatalog.GEO_MARKED_SHOT in earned)
        assertTrue(AchievementCatalog.COMPLETE_RECORD !in earned)
    }

    @Test
    fun coordinatesWithoutPhotoDoNotEarnTheGeoBadge() {
        val db = database()
        File(files, "inspections").mkdirs()
        val record = JournalFixtures.legacyRecord("nogps", "/missing.jpg")
        record.put("location", org.json.JSONObject().put("lat", 52.1).put("lon", 23.7).put("accuracy_m", 12.0))
        File(files, "inspections/nogps.json").writeText(record.toString())
        journal(db).migrateLegacy()
        assertTrue(journal(db).achievements().isEmpty())
    }

    @Test
    fun inspectionWithoutPhotoDoesNotEarnRecordBadges() {
        val db = database()
        File(files, "inspections").mkdirs()
        File(files, "inspections/noimg.json").writeText(
            JournalFixtures.legacyRecord("noimg", "/missing.jpg").toString())
        journal(db).migrateLegacy()
        val earned = journal(db).achievements()
        assertTrue(earned.isEmpty())
    }
}

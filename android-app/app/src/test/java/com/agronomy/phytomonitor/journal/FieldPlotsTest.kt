package com.agronomy.phytomonitor.journal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Plots CRUD and the place of an inspection: plot, row and coordinates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FieldPlotsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    private lateinit var database: JournalDatabase
    private var now = 1_770_000_000_000L

    @Before
    fun open() {
        files.deleteRecursively()
        files.mkdirs()
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() {
        database.close()
    }

    private fun report(name: String): File = File(files, "results/$name").apply {
        parentFile!!.mkdirs()
        writeText(JournalFixtures.prediction("/p.jpg").toString())
    }

    @Test
    fun namesAreUniqueRegardlessOfCyrillicCase() {
        val plots = FieldPlots(database) { now }
        assertTrue(plots.create("  Теплица   2 ") is PlotChange.Done)
        assertEquals("Теплица 2", plots.list().single().plot.name)
        val duplicate = plots.create("ТЕПЛИЦА 2")
        assertTrue(duplicate is PlotChange.Rejected)
        assertTrue((duplicate as PlotChange.Rejected).reason.contains("уже есть"))
        assertTrue(plots.create(" ") is PlotChange.Rejected)
        assertTrue(plots.create("а".repeat(MAX_PLOT + 1)) is PlotChange.Rejected)
        plots.create("Абрикосовый сад")
        assertEquals(listOf("Абрикосовый сад", "Теплица 2"), plots.list().map { it.plot.name })
    }

    @Test
    fun renameFollowsInspectionsAndDeleteKeepsThem() {
        val plots = FieldPlots(database) { now }
        val journal = InspectionJournal(context, database) { now }
        val saved = journal.save(report("a.json"), "заметка", "unrated", "теплица 2", row = "14")
        assertEquals("теплица 2", saved.plot)
        journal.save(report("b.json"), "", "unrated", "Теплица 2")
        val summary = plots.list().single()
        assertEquals(2, summary.inspections)

        assertTrue(plots.rename(summary.plot.id, "Теплица северная") is PlotChange.Done)
        assertEquals(setOf("Теплица северная"), journal.list().map { it.plot }.toSet())
        plots.create("Поле")
        assertTrue(plots.rename(summary.plot.id, "поле") is PlotChange.Rejected)

        plots.delete(summary.plot.id)
        val entries = journal.list()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.plot.isEmpty() })
        assertEquals("14", entries.first { it.id == saved.id }.row)
    }

    @Test
    fun testCleanupRemovesOnlyItsOwnUnusedPlotsAndSyncRows() {
        val journal = InspectionJournal(context, database) { now }
        val plots = FieldPlots(database) { now }
        val test = journal.save(report("t.json"), "TEST: x", "unrated", "TEST: грядка 7")
        val real = journal.save(report("r.json"), "настоящая", "unrated", "Теплица")
        plots.create("TEST: чужой пустой участок")
        journal.deleteForTest(test.id)
        assertEquals(setOf("Теплица", "TEST: чужой пустой участок"), plots.list().map { it.plot.name }.toSet())
        assertTrue(database.journal().pendingSync().all { it.entityId == real.id })
        assertEquals(listOf(real.id), journal.list().map { it.id })
    }

    @Test
    fun testCleanupKeepsSharedPlotAndOtherInspections() {
        val journal = InspectionJournal(context, database) { now }
        val test = journal.save(report("t.json"), "TEST: x", "unrated", "TEST: общий")
        val other = journal.save(report("r.json"), "сохранить", "unrated", "TEST: общий")
        journal.deleteForTest(test.id)
        assertEquals("TEST: общий", journal.list().single().plot)
        assertEquals(other.id, journal.list().single().id)
        assertEquals(listOf(other.id), database.journal().pendingSync().map { it.entityId })
    }

    @Test
    fun coordinatesOfTheFirstSaveStayWithThePhoto() {
        val journal = InspectionJournal(context, database) { now }
        val report = report("c.json")
        val first = journal.save(report, "", "unrated", "", row = "3", location = GeoPoint(53.9, 27.56, 7.0))
        assertEquals(53.9, first.location!!.lat, 0.0)
        val second = journal.save(report, "исправлено", "correct", "", row = "4", location = GeoPoint(10.0, 10.0, 50.0))
        assertEquals(53.9, second.location!!.lat, 0.0)
        assertEquals("4", second.row)
        val exported = JSONObject(journal.export()).getJSONArray("records").getJSONObject(0)
        assertEquals(27.56, exported.getJSONObject("location").getDouble("lon"), 0.0)
        assertTrue(journal.exportCsv().contains(";53.900000;27.560000;7"))
        val noFix = journal.save(report("d.json"), "", "unrated", "")
        assertNull(noFix.location)
    }

    @Test
    fun plantIsKeptPerPlotAndSurvivesTheArchive() {
        val journal = InspectionJournal(context, database) { now }
        journal.save(report("p1.json"), "", "unrated", "Сад", plant = "Ёж 1")
        journal.save(report("p2.json"), "", "unrated", "Сад", plant = "еж один")
        journal.save(report("p3.json"), "", "unrated", "Поле", plant = "Ёж 1")
        journal.save(report("p4.json"), "", "unrated", "", "")
        // Entries expose the plant label through the public list path.
        assertEquals(
            listOf("Ёж 1", "Ёж 1", "Ёж 1", ""),
            journal.list().sortedBy { it.created }.map { it.plant }
        )
        val before = journal.list().associate { it.id to it.plantId }
        assertEquals(2, before.values.filterNotNull().distinct().size)
        // Both labels and stable plant IDs survive an archive, including repeat observations.
        val archive = java.io.ByteArrayOutputStream()
        journal.exportArchive(archive)
        val other = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val restored = InspectionJournal(context, other) { now }
            restored.import(java.io.ByteArrayInputStream(archive.toByteArray()))
            assertEquals(before, restored.list().associate { it.id to it.plantId })
            assertEquals(
                listOf("Ёж 1", "Ёж 1", "Ёж 1", ""),
                restored.list().sortedBy { it.created }.map { it.plant }
            )
        } finally {
            other.close()
        }
    }

    @Test
    fun coordinatesSurviveTheArchive() {
        val journal = InspectionJournal(context, database) { now }
        val source = journal.save(report("e.json"), "", "unrated", "Сад", row = "2", location = GeoPoint(52.1, 23.7, 12.0))
        val archive = java.io.ByteArrayOutputStream()
        journal.exportArchive(archive)
        val other = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val restored = InspectionJournal(context, other) { now }
            restored.import(java.io.ByteArrayInputStream(archive.toByteArray()))
            val entry = restored.list().single()
            assertEquals("restored id keeps the source", source.id, entry.id)
            assertEquals(52.1, entry.location!!.lat, 0.0)
            assertEquals("2", entry.row)
            assertEquals("Сад", entry.plot)
        } finally {
            other.close()
        }
    }
}

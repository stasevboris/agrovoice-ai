package com.agronomy.phytomonitor.journal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agronomy.phytomonitor.InspectionJournal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalImportSecurityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    private val databases = mutableListOf<JournalDatabase>()

    private fun database() = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
        .allowMainThreadQueries().build().also { databases += it }

    private fun journal(db: JournalDatabase = database()) = InspectionJournal(context, db)

    private fun document(record: JSONObject) = JournalRecords.document(JSONArray().put(record), false, 10L)
        .toString().byteInputStream()

    private fun photo(): File = File(files, "photos/source.jpg").apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1, 3, 5, 7))
    }

    private fun archive(record: JSONObject, photo: File): ByteArray {
        val output = ByteArrayOutputStream()
        record.put("archive_photo", "photos/source.jpg")
        JournalArchive.write(output, JournalRecords.document(JSONArray().put(record), true, 10L).toString(),
            mapOf("photos/source.jpg" to photo))
        return output.toByteArray()
    }

    private fun entries(bytes: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        buildList {
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name)
            }
        }
    }

    @Before
    fun cleanSyntheticStorage() {
        files.deleteRecursively()
        files.mkdirs()
    }

    @After
    fun closeDatabases() {
        databases.forEach { it.close() }
    }

    @Test
    fun jsonCannotAttachAnAppPrivateFileOrExportItsBytes() {
        val privateFile = File(context.filesDir, "synthetic-private.txt").apply {
            parentFile!!.mkdirs()
            writeText("synthetic credential fixture")
        }
        val journal = journal()
        val result = journal.import(document(JournalFixtures.legacyRecord("foreign", privateFile.path)))
        val output = ByteArrayOutputStream()
        assertTrue(runCatching { journal.exportArchive(output) }.exceptionOrNull() is IOException)
        assertEquals(1, result.added)
        assertEquals(1, result.withoutPhoto)
        assertFalse(journal.list().single().photoAvailable)
        assertEquals(0, output.size())
        assertEquals("synthetic credential fixture", privateFile.readText())
    }

    @Test
    fun exportAlsoRejectsAnUnsafePathAlreadyPresentInRoom() {
        val privateFile = File(context.filesDir, "synthetic-existing-private.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(11, 12, 13))
        }
        val db = database()
        val parsed = JournalRecords.parse(JournalFixtures.legacyRecord("old-unsafe", privateFile.path))
        db.journal().saveRecord(JournalRecords.toRecord(parsed, null, null, privateFile.path),
            SyncQueueEntity(entity = "inspection", entityId = parsed.id, operation = "import", createdAt = 1L))
        val output = ByteArrayOutputStream()
        assertTrue(runCatching { journal(db).exportArchive(output) }.exceptionOrNull() is IOException)
        assertEquals(0, output.size())
        assertTrue(privateFile.readBytes().contentEquals(byteArrayOf(11, 12, 13)))
    }

    @Test
    fun traversalInLegacyIdCannotEscapePhotoDirectoryAndRoundTrips() {
        val source = photo()
        val sha = InspectionJournal.sha256(source)
        val id = "x/../../outside"
        File(files, "photos/restored_x").mkdirs()
        val bytes = archive(JournalFixtures.legacyRecord(id, "/absent.jpg", sha), source)
        val journal = journal()
        val result = journal.import(ByteArrayInputStream(bytes))
        assertEquals(1, result.added)
        val restored = File(journal.list().single().photo)
        assertEquals(File(files, "photos").canonicalFile, restored.canonicalFile.parentFile)
        assertEquals(id, journal.list().single().id)
        assertEquals(sha, InspectionJournal.sha256(restored))
        assertFalse(File(files, "outside_${sha}.jpg").exists())
        val exported = ByteArrayOutputStream()
        assertEquals(1, journal.exportArchive(exported))
        assertTrue(entries(exported.toByteArray()).filter { it != "journal.json" }.all(JournalArchive::isPhotoEntry))
        val again = journal()
        assertEquals(1, again.import(ByteArrayInputStream(exported.toByteArray())).added)
        assertEquals(id, again.list().single().id)
        assertTrue(again.list().single().photoAvailable)
    }

    @Test
    fun canonicalTraversalPathCannotAttachFileOutsidePhotos() {
        File(files, "photos").mkdirs()
        val sibling = File(files, "synthetic-private.jpg").apply { writeBytes(byteArrayOf(99)) }
        val path = File(files, "photos/../synthetic-private.jpg").path
        val journal = journal()
        val result = journal.import(document(JournalFixtures.legacyRecord("traversal", path)))
        assertEquals(1, result.withoutPhoto)
        assertFalse(journal.list().single().photoAvailable)
        assertTrue(runCatching { journal.exportArchive(ByteArrayOutputStream()) }.exceptionOrNull() is IOException)
        assertTrue(sibling.readBytes().contentEquals(byteArrayOf(99)))
    }

    @Test
    fun validLegacyPhotoRemainsAvailableAfterJsonImportAndRepeat() {
        val source = photo()
        val record = JournalFixtures.legacyRecord("legacy non-UUID", source.path, InspectionJournal.sha256(source))
        val journal = journal()
        assertEquals(0, journal.import(document(record)).withoutPhoto)
        assertTrue(journal.list().single().photoAvailable)
        assertEquals(1, journal.import(document(record)).unchanged)
        assertEquals(source.canonicalFile, File(journal.list().single().photo).canonicalFile)
        assertEquals(1, journal.exportArchive(ByteArrayOutputStream()))
    }

    @Test
    fun archiveRestoresOwnedCopyInsteadOfReusingMatchingPrivatePath() {
        val source = photo()
        val privateFile = File(context.filesDir, "synthetic-matching-private.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(source.readBytes())
        }
        val record = JournalFixtures.legacyRecord("matching", privateFile.path, InspectionJournal.sha256(source))
        val journal = journal()
        assertEquals(0, journal.import(ByteArrayInputStream(archive(record, source))).withoutPhoto)
        val restored = File(journal.list().single().photo)
        assertEquals(File(files, "photos").canonicalFile, restored.canonicalFile.parentFile)
        assertTrue(restored.readBytes().contentEquals(source.readBytes()))
        assertTrue(privateFile.readBytes().contentEquals(source.readBytes()))
    }
}

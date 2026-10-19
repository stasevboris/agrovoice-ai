package com.agronomy.phytomonitor

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agronomy.phytomonitor.journal.JournalDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Restore into an isolated database and directory; never replace the user's journal or photos. */
@RunWith(AndroidJUnit4::class)
class JournalArchiveDeviceTest {
    @Test
    fun archiveRestoresPhotosAndRepeatedImportKeepsRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = InspectionJournal(context)
        val before = source.list().associate { it.id to source.record(it.id).toString() }
        assertTrue("The device check needs a saved inspection with a photo", before.isNotEmpty())
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "archive-check-").toFile()
        val isolatedContext = RestoreContext(context, directory)
        val database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).build()
        try {
            val exported = File(directory, "exported.zip")
            val photoCount = exported.outputStream().use(source::exportArchive)
            assertTrue("The device check needs at least one available photo", photoCount > 0)
            val archive = archiveFromAnotherDevice(exported, directory)
            val restored = InspectionJournal(isolatedContext, database)
            val report = archive.inputStream().use(restored::import)
            assertTrue(report.issues.toString(), report.issues.isEmpty())
            assertEquals(before.size, report.added)
            assertEquals(before.keys, restored.list().map { it.id }.toSet())
            for (entry in source.list()) {
                val original = source.details(entry.id)!!
                val copy = restored.details(entry.id)!!
                assertEquals(original.inspection.note, copy.inspection.note)
                assertEquals(original.inspection.userReview, copy.inspection.userReview)
                val expectedPrediction = JSONObject(original.inspection.predictionJson)
                val actualPrediction = JSONObject(copy.inspection.predictionJson)
                    .put("photo", expectedPrediction.getString("photo"))
                assertEquals(expectedPrediction.toString(), actualPrediction.toString())
                assertEquals(
                    original.photos.flatMap { it.detections }.map { it.copy(id = 0) },
                    copy.photos.flatMap { it.detections }.map { it.copy(id = 0) }
                )
                if (entry.photoAvailable) {
                    val originalPhoto = File(original.photos.single().photo.localPath)
                    val restoredPhoto = File(copy.photos.single().photo.localPath)
                    assertTrue(restoredPhoto.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                    assertEquals(InspectionJournal.sha256(originalPhoto), InspectionJournal.sha256(restoredPhoto))
                }
            }
            val repeated = archive.inputStream().use(restored::import)
            assertTrue(repeated.issues.toString(), repeated.issues.isEmpty())
            assertEquals(0, repeated.added)
            assertEquals(0, repeated.updated)
            assertEquals(before.size, repeated.unchanged)
            assertEquals(before, source.list().associate { it.id to source.record(it.id).toString() })
            val evidence = File(context.getExternalFilesDir(null), "features_v1_phone").apply { mkdirs() }
            File(evidence, "archive_restore.json").writeText(
                JSONObject()
                    .put("status", "passed")
                    .put("records", report.added)
                    .put("photos", photoCount)
                    .put("missing_photos", report.withoutPhoto)
                    .put("repeat_unchanged", repeated.unchanged)
                    .put("source_unchanged", true)
                    .put("scope", "Android archive/Room restore in an isolated directory, with old photo paths changed to nonexistent paths to simulate another phone. Photo bytes unchanged; no document picker or replacement of the user's journal.")
                    .toString(2)
            )
        } finally {
            database.close()
            assertTrue("Temporary restore files were not removed", directory.deleteRecursively())
        }
    }

    private fun archiveFromAnotherDevice(exported: File, directory: File): File {
        val relocated = File(directory, "other-device.zip")
        ZipInputStream(exported.inputStream()).use { input ->
            ZipOutputStream(relocated.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "journal.json") {
                        val document = JSONObject(input.readBytes().toString(Charsets.UTF_8))
                        val records = document.getJSONArray("records")
                        for (index in 0 until records.length()) {
                            records.getJSONObject(index).getJSONObject("prediction")
                                .put("photo", File(directory, "absent-device/$index.jpg").path)
                        }
                        output.write(document.toString().toByteArray(Charsets.UTF_8))
                    } else {
                        input.copyTo(output)
                    }
                    output.closeEntry()
                }
            }
        }
        return relocated
    }

    private class RestoreContext(context: Context, private val directory: File) : ContextWrapper(context) {
        override fun getExternalFilesDir(type: String?): File = File(directory, "files").apply { mkdirs() }
        override fun getFilesDir(): File = getExternalFilesDir(null)
        override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
    }
}

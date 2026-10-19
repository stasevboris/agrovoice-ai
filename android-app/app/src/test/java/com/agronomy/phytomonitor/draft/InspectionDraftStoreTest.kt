package com.agronomy.phytomonitor.draft

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InspectionDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun restartRestoresAllMetadataAndDiscardLeavesPhotoAndReport() {
        val picture = temporary.newFile("photo.jpg").apply { writeText("original photo") }
        val report = temporary.newFile("result.json").apply { writeText("original result") }
        val file = File(temporary.root, "draft.json")
        val draft = InspectionDraft(photo = picture.path, reportName = report.name,
            note = "Лист в тени", plot = "Теплица", row = "14", plant = "Растение 3", review = "uncertain")
        InspectionDraftStore(file).write(draft)
        assertEquals(draft, InspectionDraftStore(file).read())
        assertTrue(InspectionDraftStore(file).clear(draft.id))
        assertNull(InspectionDraftStore(file).read())
        assertEquals("original photo", picture.readText())
        assertEquals("original result", report.readText())
    }

    @Test
    fun interruptedReplacementRestoresLastCommittedSnapshot() {
        val file = File(temporary.root, "draft.json")
        val draft = InspectionDraft(photo = "/photo.jpg", note = "Проверенная заметка")
        val store = InspectionDraftStore(file)
        store.write(draft)
        // Emulate a process ending while writing the replacement, before its atomic rename.
        File(file.path + ".pending").writeText("{partial")
        assertEquals(draft, InspectionDraftStore(file).read())
    }

    @Test
    fun completionOfOldSaveDoesNotClearNewDraft() {
        val store = InspectionDraftStore(File(temporary.root, "draft.json"))
        val old = InspectionDraft(photo = "/old.jpg")
        val next = InspectionDraft(photo = "/next.jpg")
        store.write(old)
        store.write(next)
        assertEquals(next, store.read())
        assertFalse(store.clear(old.id))
        assertEquals(next, store.read())
    }

    @Test
    fun corruptSnapshotIsKeptForRecovery() {
        val file = File(temporary.root, "draft.json").apply { writeText("{damaged") }
        assertThrows(Exception::class.java) { InspectionDraftStore(file).read() }
        assertEquals("{damaged", file.readText())
    }

    @Test
    fun explicitRecoveryPreservesCorruptBytesAndAllowsNewInspection() {
        val file = File(temporary.root, "draft.json").apply { writeText("{damaged") }
        val store = InspectionDraftStore(file).writer()
        assertThrows(Exception::class.java) { store.read() }
        val recovery = requireNotNull(store.preserveDamaged())
        assertEquals("{damaged", recovery.readText())
        assertNull(store.read())
        val next = InspectionDraft(photo = "/new.jpg", note = "Новый осмотр")
        store.write(next)
        assertEquals(next, store.read())
        assertEquals("{damaged", recovery.readText())
    }

    @Test
    fun newerSavedRecordWinsOverInterruptedSaveAndUncommittedEditRemains() {
        val pending = InspectionDraft(photo = "/photo.jpg", saveRequested = true, savedBeforeVersion = 100L)
        assertTrue(pending.saveCompleted(101L))
        assertFalse(pending.saveCompleted(100L))
        assertFalse(pending.saveCompleted(null))
        assertTrue(pending.copy(savedBeforeVersion = null).saveCompleted(100L))
        assertFalse(pending.copy(saveRequested = false).saveCompleted(101L))
    }
}

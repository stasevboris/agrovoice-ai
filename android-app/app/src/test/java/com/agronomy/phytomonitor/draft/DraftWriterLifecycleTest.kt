package com.agronomy.phytomonitor.draft

import com.agronomy.phytomonitor.ml.SerialModelWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class DraftWriterLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun analysisFinishingInOldWorkerCannotOverwriteNewOwnerEdits() = runBlocking {
        val file = File(temporary.root, "draft.json")
        val original = InspectionDraft(photo = "/photo.jpg", note = "before exit")
        val oldStore = InspectionDraftStore(file).writer()
        oldStore.write(original)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        val oldWorker = SerialModelWork(onFailure = { failures += it }, onClosed = {})
        oldWorker.submit {
            started.complete(Unit)
            finish.await()
            oldStore.write(original.copy(note = "old analysis finished"))
        }
        withTimeout(5000) { started.await() }
        oldWorker.close() // close drains the already accepted analysis, like ViewModel.onCleared.
        val newStore = InspectionDraftStore(file).writer()
        val updated = original.copy(note = "new owner corrected the note")
        val newWorker = SerialModelWork(onFailure = { throw it }, onClosed = {})
        newWorker.submit { newStore.write(updated) }
        newWorker.close()
        withTimeout(5000) { newWorker.awaitClosed() }
        finish.complete(Unit)
        withTimeout(5000) { oldWorker.awaitClosed() }
        assertTrue(failures.single() is StaleDraftWriterException)
        assertEquals(updated, newStore.read())
    }

    @Test
    fun oldWorkerCannotResurrectDraftDiscardedByNewOwner() = runBlocking {
        val file = File(temporary.root, "draft.json")
        val original = InspectionDraft(photo = "/photo.jpg")
        val oldStore = InspectionDraftStore(file).writer()
        oldStore.write(original)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        val oldWorker = SerialModelWork(onFailure = { failures += it }, onClosed = {})
        oldWorker.submit {
            started.complete(Unit)
            finish.await()
            oldStore.write(original)
        }
        withTimeout(5000) { started.await() }
        oldWorker.close()
        val newStore = InspectionDraftStore(file).writer()
        assertTrue(newStore.clear(original.id))
        finish.complete(Unit)
        withTimeout(5000) { oldWorker.awaitClosed() }
        assertTrue(failures.single() is StaleDraftWriterException)
        assertNull(newStore.read())
    }

    @Test
    fun oldWriterCannotCommitJournalOrArchiveNewOwnersSnapshot() {
        val file = File(temporary.root, "draft.json")
        val old = InspectionDraftStore(file).writer()
        val current = InspectionDraftStore(file).writer()
        val snapshot = InspectionDraft(photo = "/new.jpg")
        current.write(snapshot)
        var committed = false
        assertThrows(StaleDraftWriterException::class.java) { old.withOwnership { committed = true } }
        assertFalse(committed)
        assertThrows(StaleDraftWriterException::class.java) { old.preserveDamaged() }
        assertEquals(snapshot, current.read())
    }

    @Test
    fun cleanupRevokesDelayedWriterBeforeRestoringUnparsedOriginalBytes() = runBlocking {
        val file = File(temporary.root, "draft.json")
        val original = "{original damaged bytes".toByteArray()
        val old = InspectionDraftStore(file).writer()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        val worker = SerialModelWork(onFailure = { failures += it }, onClosed = {})
        worker.submit {
            started.complete(Unit)
            release.await()
            old.write(InspectionDraft(photo = "/fixture.jpg"))
        }
        withTimeout(5000) { started.await() }
        worker.close()
        val cleanup = InspectionDraftStore(file).writer()
        cleanup.withOwnership {
            val replacement = File(temporary.root, "restore.pending")
            replacement.writeBytes(original)
            Files.move(replacement.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        release.complete(Unit)
        withTimeout(5000) { worker.awaitClosed() }
        assertTrue(failures.single() is StaleDraftWriterException)
        assertArrayEquals(original, file.readBytes())
    }
}

package com.agronomy.phytomonitor.journal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JournalStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun incompleteOldCopyDoesNotBlockFreshCopyAndExistingTargetsAreNotReplaced() {
        val folder = temporary.newFolder()
        val source = File(folder, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unfinished = File(folder, "agrovoice_restore_old.part").apply { writeBytes(byteArrayOf(1)) }
        val target = File(folder, "restored.jpg")
        JournalStorage.copyPhoto(source, target)
        assertArrayEquals(source.readBytes(), target.readBytes())
        assertTrue(unfinished.isFile)
        source.writeBytes(byteArrayOf(9))
        assertTrue(runCatching { JournalStorage.copyPhoto(source, target) }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertEquals(1, folder.listFiles()!!.count { it.extension == "part" })
    }

    @Test
    fun reserveAndOverflowChecksRejectBeforeCreatingAFile() {
        val folder = temporary.newFolder()
        assertTrue(runCatching { JournalStorage.requireSpace(folder, Long.MAX_VALUE) { Long.MAX_VALUE } }.isFailure)
        assertTrue(runCatching { JournalStorage.requireSpace(folder, 1) { JournalStorage.RESERVE_BYTES } }.isFailure)
        JournalStorage.requireSpace(folder, 1) { JournalStorage.RESERVE_BYTES + 1 }
        assertTrue(folder.listFiles()!!.isEmpty())
    }
}

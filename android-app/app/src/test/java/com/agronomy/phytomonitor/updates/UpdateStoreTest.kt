package com.agronomy.phytomonitor.updates

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun release(bytes: ByteArray, version: String = "test") = UpdateRelease.parse(JSONObject()
        .put("id", "kb-$version").put("kind", "kb").put("version", version)
        .put("file_name", "kb.json").put("bytes", bytes.size)
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        .put("download_path", "/updates/kb-$version/download")
        .put("metadata", JSONObject().put("schema", "agrovoice-kb-v1").put("region", "BY")))

    @Test fun corruptOrInterruptedDownloadDoesNotReplaceActive() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val old = release("old".toByteArray(), "old")
        store.stage(old, ByteArrayInputStream("old".toByteArray()))
        store.activate(old)
        val next = release("next".toByteArray(), "next")
        assertThrows(IllegalArgumentException::class.java) {
            store.stage(next, ByteArrayInputStream("bad!".toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.stage(next, ByteArrayInputStream("ne".toByteArray()))
        }
        assertEquals(old.sha256, store.available("kb").first().sha256)
        assertEquals("old", store.file(old).readText())
    }

    @Test fun corruptedActiveFallsBackToPreviousAfterNewProcess() {
        val folder = temporary.newFolder()
        val store = VerifiedUpdateStore(folder)
        val old = release("old".toByteArray(), "old")
        val next = release("next".toByteArray(), "next")
        for (item in listOf(old, next)) {
            store.stage(item, ByteArrayInputStream(item.version.toByteArray()))
            store.activate(item)
        }
        assertEquals(next.sha256, VerifiedUpdateStore(folder).available("kb").first().sha256)
        store.file(next).writeText("broken")
        assertEquals(old.sha256, VerifiedUpdateStore(folder).available("kb").first().sha256)
    }

    @Test fun traversalUnknownSchemaAndExternalDownloadAreRejected() {
        val item = release("test".toByteArray()).toJson()
        item.put("file_name", "../outside.json")
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.parse(item) }
        item.put("file_name", "kb.json").put("download_path", "https://other.invalid/model")
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.parse(item) }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.feed(JSONObject().put("schema_version", 9).put("releases", JSONArray()).toString())
        }
    }

    @Test fun activationNeedsVerifiedBytesAndCancellationDoesNotPublish() {
        val store = VerifiedUpdateStore(temporary.newFolder())
        val item = release("test".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { store.activate(item) }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            store.stage(item, ByteArrayInputStream("test".toByteArray())) { false }
        }
        assertTrue(store.available("kb").isEmpty())
    }
}

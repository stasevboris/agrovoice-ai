package com.agronomy.phytomonitor.sync

import com.agronomy.phytomonitor.updates.UpdateRelease
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncUpdateStateTest {
    private val release = UpdateRelease("model-v1", "model", "v1", "model.onnx", 1, "a".repeat(64), JSONObject())

    @Test fun newAccountDropsOldFeedButKeepsLocalInstallationState() {
        val previous = SyncUiState(generation = "old", updates = listOf(release), offeredModel = release,
            activeModel = release, pendingModelRestart = true)
        val next = previous.forSession(SyncSession("https://new.example", "new-user", "user", "token", "new"))
        assertTrue(next.updates.isEmpty())
        assertNull(next.offeredModel)
        assertEquals(release, next.activeModel)
        assertTrue(next.pendingModelRestart)
        assertTrue(previous.forSession(null).updates.isEmpty())
    }

    @Test fun lateFeedCannotRepopulateAfterAccountChange() {
        val next = SyncUiState(generation = "new")
        assertEquals(next, next.withFeed("old", listOf(release), release))
        assertEquals(release, next.withFeed("new", listOf(release), release).offeredModel)
    }
}

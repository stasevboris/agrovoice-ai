package com.agronomy.phytomonitor.journal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SeriesRecordsTest {
    private fun frame(id: String, order: Int): JSONObject = JSONObject()
        .put("id", id).put("order", order).put("role", "unspecified")
        .put("photo_path", "/absent/$id.jpg").put("photo_sha256", "a".repeat(64))
        .put("width", 20).put("height", 10).put("report_name", "$id.json").put("report_sha256", "")
        .put("prediction_json", JournalFixtures.prediction("/absent/$id.jpg", "a".repeat(64)).toString())

    private fun record(frames: JSONArray) = JournalFixtures.legacyRecord("series", "/absent/first.jpg")
        .put("frames", frames)

    @Test
    fun versionThreeIsAcceptedAlongsideLegacyVersions() {
        val record = record(JSONArray().put(frame("first", 0)).put(frame("second", 1)))
        val document = JSONObject().put("schema", "agrovoice-inspections-v3").put("records", JSONArray().put(record))
        val (parsed, error) = JournalRecords.records(document.toString())
        assertNull(error)
        assertEquals(1, parsed.size)
        assertTrue(parsed.single().isSuccess)
    }

    @Test
    fun frameCannotReplaceThePhotoHashSavedInItsAnalysis() {
        val changed = frame("second", 1).put("photo_sha256", "b".repeat(64))
        val parsed = JournalRecords.parseObject(record(JSONArray().put(frame("first", 0)).put(changed)), "fixture")
        assertTrue("Analysis from one photo must not be attached to different bytes", parsed.isFailure)
    }

    @Test
    fun missingOuterHashCannotEraseTheHashSavedInItsAnalysis() {
        val missing = frame("second", 1).put("photo_sha256", "")
        val parsed = JournalRecords.parseObject(record(JSONArray().put(frame("first", 0)).put(missing)), "fixture")
        assertTrue("Ambiguous photo binding must be rejected", parsed.isFailure)
    }

    @Test
    fun duplicateBlankOrAmbiguousFrameIdsAndOrderAreRejected() {
        val cases = listOf(
            JSONArray().put(frame("duplicate", 0)).put(frame("duplicate", 1)),
            JSONArray().put(frame("", 0)),
            JSONArray().put(frame("first", 0).put("id", 5)),
            JSONArray().put(frame("first", 0).put("id", JSONObject().put("bad", "id"))),
            JSONArray().put(frame("first", 0)).put(frame("second", 0)),
            JSONArray().put(frame("first", 0).put("order", 0.5)),
            JSONArray().put(frame("first", 0).put("order", "0")),
            JSONArray().put(frame("first", -1)),
            JSONArray().put(frame("first", 0).put("role", "invented-view")),
            JSONArray().put(frame("first", 0).put("prediction_json", "{broken")),
            JSONArray().put(frame("first", 0).put("photo_sha256", "invalid-sha")),
            JSONArray((0..10).map { frame("frame-$it", it) })
        )
        for (frames in cases) {
            assertTrue(frames.toString(), JournalRecords.parseObject(record(frames), "fixture").isFailure)
        }
    }
}

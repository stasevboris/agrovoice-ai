package com.agronomy.phytomonitor.journal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JournalRecordsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun legacyRecordBecomesInspectionPhotoAndDetections() {
        val parsed = JournalRecords.parse(JournalFixtures.legacyRecord("a1", "/data/photos/import_1.jpg", "ff00"))
        val record = JournalRecords.toRecord(parsed, plotId = "plot-1", plantId = null, photoPath = "/data/photos/import_1.jpg")
        assertEquals("a1", record.inspection.id)
        assertEquals("plot-1", record.inspection.plotId)
        assertEquals("incorrect", record.inspection.userReview)
        assertEquals("yolo26n", record.inspection.modelVersion)
        assertEquals("ff00", record.photo.sha256)
        assertEquals(1536, record.photo.width)
        assertEquals(1, record.detections.size)
        assertEquals("Томат: фитофтороз", record.detections.single().labelRu)
        assertEquals(220.0, record.detections.single().y2, 0.0)
        assertEquals("/data/photos/import_1.jpg", JSONObject(record.inspection.predictionJson).getString("photo"))
    }

    @Test
    fun brokenRecordsGiveAReasonInsteadOfDisappearing() {
        val cases = mapOf(
            "{не json" to "не является JSON",
            JSONObject().put("id", "x").toString() to "нет отчёта анализа",
            JournalFixtures.legacyRecord("x", "/p.jpg").put("review", "maybe").toString() to "неизвестная оценка",
            JournalFixtures.legacyRecord("x", "/p.jpg").put("report_name", "").toString() to "нет поля report_name",
            JournalFixtures.legacyRecord("x", "/p.jpg").put("note", "а".repeat(MAX_NOTE + 1)).toString() to "длиннее",
            JournalFixtures.legacyRecord("x", "/p.jpg").apply { getJSONObject("prediction").remove("detections") }.toString() to "списка рамок",
            JournalFixtures.legacyRecord("x", "/p.jpg").put("created_epoch_ms", 0).toString() to "нет даты"
        )
        cases.forEach { (text, reason) ->
            val result = JournalRecords.parseText(text, "f.json")
            assertTrue(text.take(40), result.isFailure)
            val message = result.exceptionOrNull()!!.message!!
            assertTrue(message, message.startsWith("f.json: ") && reason in message)
        }
    }

    @Test
    fun exportedRecordReadsBackWithTheSameFields() {
        val parsed = JournalRecords.parse(JournalFixtures.legacyRecord("b2", "/p.jpg", "a".repeat(64)))
        val record = JournalRecords.toRecord(parsed, "plot-1", null, "/restored.jpg")
        val details = InspectionWithDetails(
            record.inspection,
            PlotEntity("plot-1", "Теплица 2", updatedAt = 1L),
            listOf(PhotoWithDetections(record.photo, record.detections))
        )
        val json = JournalRecords.toJson(details, archivePhoto = "photos/b2.jpg")
        val again = JournalRecords.parse(json)
        assertEquals(parsed.id, again.id)
        assertEquals(parsed.note, again.note)
        assertEquals("Теплица 2", again.plot)
        assertEquals("photos/b2.jpg", again.archivePhoto)
        assertEquals("/restored.jpg", json.getString("photo_path"))
        assertEquals(parsed.reportName, again.reportName)
    }

    @Test
    fun documentsOfBothVersionsAreAcceptedAndOthersRejected() {
        val records = JSONArray().put(JournalFixtures.legacyRecord("c3", "/p.jpg")).put("строка вместо записи")
        val v1 = JSONObject().put("schema", LEGACY_JOURNAL_SCHEMA).put("records", records).toString()
        val (parsed, problem) = JournalRecords.records(v1)
        assertNull(problem)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].isSuccess)
        assertTrue(parsed[1].exceptionOrNull()!!.message!!.startsWith("запись 2"))
        val v2 = JournalRecords.document(JSONArray(), containsPhotos = true, exportedAt = 5L).toString()
        assertNull(JournalRecords.records(v2).second)
        assertNotNull(JournalRecords.records(JSONObject().put("schema", "other").toString()).second)
        assertNotNull(JournalRecords.records("\uFEFF{").second)
    }

    @Test
    fun csvCellsAreQuotedAndFormulasNeutralised() {
        assertEquals("Теплица", JournalCsv.cell("Теплица"))
        assertEquals("\"ряд; 4\"", JournalCsv.cell("ряд; 4"))
        assertEquals("\"сказал \"\"стоп\"\"\"", JournalCsv.cell("сказал \"стоп\""))
        assertEquals("\"две\nстроки\"", JournalCsv.cell("две\nстроки"))
        assertEquals("'=HYPERLINK(1)", JournalCsv.cell("=HYPERLINK(1)"))
        assertEquals("'-5", JournalCsv.cell("-5"))
    }

    @Test
    fun csvHasHeaderAndOneLinePerInspection() {
        val parsed = JournalRecords.parse(JournalFixtures.legacyRecord("d4", "/p.jpg", note = "Пятна; жёлтые"))
        val record = JournalRecords.toRecord(parsed, "plot-1", null, "/p.jpg")
        val details = InspectionWithDetails(record.inspection, PlotEntity("plot-1", "Теплица 2", updatedAt = 1L), listOf(PhotoWithDetections(record.photo, record.detections)))
        val csv = JournalCsv.write(listOf(details)) { false }
        assertTrue(csv.startsWith("\uFEFFid;дата;участок"))
        val lines = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].startsWith("d4;"))
        assertTrue(lines[1].contains(";Теплица 2;"))
        assertTrue(lines[1].contains("\"Пятна; жёлтые\""))
        assertTrue(lines[1], lines[1].endsWith(";нет;;;"))
    }

    @Test
    fun archiveRoundTripKeepsJournalAndPhotos() {
        val photo = temporary.newFile("import_1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val output = ByteArrayOutputStream()
        JournalArchive.write(output, "{\"schema\":\"x\"}", mapOf(JournalArchive.photoEntry("e5", photo) to photo))
        val content = JournalArchive.read(ByteArrayInputStream(output.toByteArray()), temporary.newFolder("staging"))
        assertEquals("{\"schema\":\"x\"}", content.journal)
        assertEquals(listOf("photos/e5.jpg"), content.photos.keys.toList())
        assertTrue(content.photos.getValue("photos/e5.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(content.issues.isEmpty())
    }

    @Test
    fun archiveEntriesOutsideTheLayoutAreNotExtracted() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            listOf("../evil.jpg", "photos/../../evil.jpg", "photos/x/y.jpg", "script.sh", "photos/ok.exe").forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.write(byteArrayOf(9))
                zip.closeEntry()
            }
        }
        val staging = temporary.newFolder("staging")
        val content = JournalArchive.read(ByteArrayInputStream(bytes.toByteArray()), staging)
        assertTrue(content.photos.isEmpty())
        assertNull(content.journal)
        assertEquals(6, content.issues.size)
        assertTrue(staging.listFiles()!!.isEmpty())
        assertFalse(java.io.File(temporary.root, "evil.jpg").exists())
    }
}

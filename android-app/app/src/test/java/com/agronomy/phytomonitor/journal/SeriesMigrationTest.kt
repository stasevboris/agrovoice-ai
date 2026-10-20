package com.agronomy.phytomonitor.journal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesMigrationTest {
    @Test
    fun oldRowsAndDetectionsSurviveAndOnlyProvenPrimaryGetsAnalysis() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "series-migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val entities = JSONObject(File("schemas/com.agronomy.phytomonitor.journal.JournalDatabase/4.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        val prediction = JournalFixtures.prediction("/old-photo.jpg").toString()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (item in 0 until indices.length()) old.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            fun values(table: String): ContentValues {
                val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
                val fields = entity.getJSONArray("fields")
                return ContentValues().apply {
                    for (index in 0 until fields.length()) {
                        val field = fields.getJSONObject(index)
                        if (field.optBoolean("notNull")) {
                            if (field.getString("affinity") == "TEXT") put(field.getString("columnName"), "")
                            else put(field.getString("columnName"), 0)
                        }
                    }
                }
            }
            old.insertOrThrow("inspection", null, values("inspection").apply {
                put("id", "inspection")
                put("report_name", "original.json")
                put("report_sha256", "old-report-sha")
                put("prediction_json", prediction)
                put("note", "Не потерять историю")
            })
            for (id in listOf("inspection", "unknown-extra")) {
                old.insertOrThrow("photo", null, values("photo").apply {
                    put("id", id)
                    put("inspection_id", "inspection")
                    put("local_path", "/$id.jpg")
                    put("sha256", "sha-$id")
                })
            }
            old.insertOrThrow("detection", null, values("detection").apply {
                put("id", 1)
                put("photo_id", "inspection")
                put("label_ru", "Сохранённая метка")
                put("confidence", 0.7)
            })
            old.version = 4
        }
        val migrated = Room.databaseBuilder(context, JournalDatabase::class.java, name)
            .addMigrations(JournalDatabase.MIGRATION_3_4, JournalDatabase.MIGRATION_4_5)
            .allowMainThreadQueries().build()
        try {
            val details = migrated.journal().inspectionDetails("inspection")!!
            assertEquals("Не потерять историю", details.inspection.note)
            assertEquals(2, details.photos.size)
            assertEquals("Сохранённая метка", details.photos.single { it.photo.id == "inspection" }.detections.single().labelRu)
            val columns = migrated.query("PRAGMA table_info(photo)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue(columns.toString(), columns.containsAll(setOf("frame_order", "frame_role", "report_name", "report_sha256", "prediction_json")))
            migrated.query("SELECT frame_order, frame_role, prediction_json, report_name, report_sha256 FROM photo WHERE id = 'inspection'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("unspecified", it.getString(1))
                assertEquals(prediction, it.getString(2))
                assertEquals("original.json", it.getString(3))
                assertEquals("old-report-sha", it.getString(4))
            }
            migrated.query("SELECT frame_order, prediction_json FROM photo WHERE id = 'unknown-extra'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertTrue(it.isNull(1))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}

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
class PlantMigrationTest {
    @Test
    fun versionOneMigratesWithoutLosingPhotoOrDetections() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.agronomy.phytomonitor.journal.JournalDatabase/1.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until schema.length()) {
                val entity = schema.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            for (table in listOf("inspection", "photo", "detection")) {
                val entity = (0 until schema.length()).map { schema.getJSONObject(it) }.first { it.getString("tableName") == table }
                val fields = entity.getJSONArray("fields")
                val values = ContentValues()
                for (i in 0 until fields.length()) {
                    val field = fields.getJSONObject(i)
                    if (field.optBoolean("notNull", false)) {
                        if (field.getString("affinity") == "TEXT") values.put(field.getString("columnName"), "")
                        else values.put(field.getString("columnName"), 0)
                    }
                }
                when (table) {
                    "inspection" -> {
                        values.put("id", "i")
                        values.put("note", "Не потерять заметку")
                    }
                    "photo" -> {
                        values.put("id", "p")
                        values.put("inspection_id", "i")
                        values.put("sha256", "photo-sha")
                    }
                    "detection" -> {
                        values.put("id", 1)
                        values.put("photo_id", "p")
                        values.put("label_ru", "Сохранённая метка")
                        values.put("confidence", 0.7)
                    }
                }
                old.insertOrThrow(table, null, values)
            }
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, JournalDatabase::class.java, name)
            .addMigrations(JournalDatabase.MIGRATION_1_2, JournalDatabase.MIGRATION_2_3, JournalDatabase.MIGRATION_3_4, JournalDatabase.MIGRATION_4_5, JournalDatabase.MIGRATION_5_6)
            .allowMainThreadQueries().build()
        try {
            val record = migrated.journal().inspectionDetails("i")!!
            assertEquals("Не потерять заметку", record.inspection.note)
            assertEquals("photo-sha", record.photos.single().photo.sha256)
            assertNull(record.photos.single().photo.plantId)
            assertEquals("Сохранённая метка", record.photos.single().detections.single().labelRu)
            assertEquals(0.7, record.photos.single().detections.single().confidence, 0.0)
            assertTrue(migrated.sync().records("http://127.0.0.1|test-user").isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}

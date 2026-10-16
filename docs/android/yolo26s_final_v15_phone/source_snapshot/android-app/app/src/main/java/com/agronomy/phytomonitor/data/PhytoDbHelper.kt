package com.agronomy.phytomonitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class PhytoDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "phytomonitor_sqlite.db"
        const val DATABASE_VERSION = 1

        const val TABLE_PATHOGENS = "pathogens"
        const val TABLE_TREATMENTS = "treatments"
        const val TABLE_INSPECTION_LOGS = "inspection_logs"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_PATHOGENS (
                id TEXT PRIMARY KEY,
                name_ru TEXT NOT NULL,
                latin_name TEXT NOT NULL,
                crop TEXT NOT NULL,
                warning_threshold_pct REAL DEFAULT 10.0,
                symptoms_description TEXT
            );
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_TREATMENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pathogen_id TEXT NOT NULL,
                is_biological INTEGER NOT NULL DEFAULT 0,
                trade_name TEXT NOT NULL,
                active_substance TEXT NOT NULL,
                dosage_per_10l TEXT NOT NULL,
                waiting_days INTEGER NOT NULL DEFAULT 3,
                hazard_human_class TEXT DEFAULT '3',
                hazard_bees_class TEXT DEFAULT '3',
                instructions TEXT,
                FOREIGN KEY (pathogen_id) REFERENCES $TABLE_PATHOGENS(id) ON DELETE CASCADE
            );
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_INSPECTION_LOGS (
                id TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                greenhouse_block TEXT NOT NULL,
                row_number INTEGER NOT NULL,
                photo_local_path TEXT NOT NULL,
                detected_pathogen_id TEXT,
                confidence REAL NOT NULL,
                infestation_rate_pct REAL NOT NULL,
                assigned_treatment_name TEXT,
                voice_prompt_command TEXT,
                is_synced INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (detected_pathogen_id) REFERENCES $TABLE_PATHOGENS(id) ON DELETE SET NULL
            );
        """.trimIndent())

        seedInitialPathogens(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_INSPECTION_LOGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TREATMENTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PATHOGENS")
        onCreate(db)
    }

    private fun seedInitialPathogens(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("""
                INSERT OR IGNORE INTO $TABLE_PATHOGENS (id, name_ru, latin_name, crop, warning_threshold_pct, symptoms_description) VALUES
                ('powdery_mildew', 'Мучнистая роса', 'Podosphaera xanthii', 'Огурец', 15.0, 'Белый мучнистый налет на листьях'),
                ('late_blight', 'Фитофтороз', 'Phytophthora infestans', 'Томат', 10.0, 'Бурые расплывчатые пятна с белесым налетом'),
                ('gray_mold', 'Серая гниль', 'Botrytis cinerea', 'Томат / Перец', 12.0, 'Серый пушистый налет в местах слома пасынков'),
                ('spider_mite', 'Обыкновенный паутинный клещ', 'Tetranychus urticae', 'Огурец / Томат', 8.0, 'Точечные хлорозы и паутина на нижней стороне листа'),
                ('whitefly', 'Тепличная белокрылка', 'Trialeurodes vaporariorum', 'Томат', 10.0, 'Колонии на абаксиальной стороне листа');
            """.trimIndent())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertInspectionLog(
        block: String,
        rowNumber: Int,
        photoPath: String,
        pathogenId: String?,
        confidence: Float,
        infestationRate: Float,
        treatmentName: String?
    ): String {
        val uuid = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", uuid)
            put("timestamp", System.currentTimeMillis())
            put("greenhouse_block", block)
            put("row_number", rowNumber)
            put("photo_local_path", photoPath)
            put("detected_pathogen_id", pathogenId)
            put("confidence", confidence)
            put("infestation_rate_pct", infestationRate)
            put("assigned_treatment_name", treatmentName)
            put("is_synced", 0)
        }
        writableDatabase.insert(TABLE_INSPECTION_LOGS, null, values)
        return uuid
    }

    fun getUnsyncedCount(): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_INSPECTION_LOGS WHERE is_synced = 0", null
        )
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }
}
package com.example.runtrail.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * ฐานข้อมูลในเครื่อง (MA10 SQLite) — แหล่งข้อมูลหลักของแอป ใช้ได้แม้ไม่มีเน็ต
 *
 * runs        การวิ่ง 1 แถวต่อรอบ (รวม draft ที่ยังวิ่งไม่จบ: end_time IS NULL)
 * run_points  จุด GPS ทั้งหมด (seq เรียงตามเวลา)
 * splits      ข้อมูลรายกิโลเมตร คำนวณครั้งเดียวตอนบันทึก
 */
class RunDb(context: Context, name: String? = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                owner TEXT,
                source TEXT NOT NULL,
                title TEXT NOT NULL,
                note TEXT,
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                timezone TEXT,
                distance_m REAL NOT NULL DEFAULT 0,
                moving_time_s INTEGER NOT NULL DEFAULT 0,
                calories REAL NOT NULL DEFAULT 0,
                elevation_gain_m INTEGER NOT NULL DEFAULT 0,
                cadence_spm INTEGER NOT NULL DEFAULT 0,
                temp_c INTEGER,
                photo_path TEXT,
                photo_sync TEXT NOT NULL DEFAULT 'NONE',
                share_code TEXT,
                share_url TEXT,
                share_hide_ends INTEGER NOT NULL DEFAULT 1,
                share_show_photo INTEGER NOT NULL DEFAULT 1,
                share_show_kcal INTEGER NOT NULL DEFAULT 0,
                verified INTEGER,
                verify_reason TEXT,
                server_distance_m REAL,
                sync_status TEXT NOT NULL DEFAULT 'PENDING',
                sync_error TEXT,
                on_server INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                thumb TEXT,
                pauses TEXT,
                feel INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX runs_start ON runs (start_time)")
        db.execSQL(
            """
            CREATE TABLE run_points (
                run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
                seq INTEGER NOT NULL,
                lat REAL NOT NULL, lng REAL NOT NULL, time INTEGER NOT NULL,
                alt REAL NOT NULL, acc REAL NOT NULL,
                PRIMARY KEY (run_id, seq)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE splits (
                run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
                km INTEGER NOT NULL, pace_s INTEGER NOT NULL, elev_delta_m INTEGER NOT NULL,
                PRIMARY KEY (run_id, km)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: ความรู้สึกหลังวิ่ง 0–5 ดาว (RatingBar ในหน้าเพิ่ม/แก้ไข) — ข้อมูลเดิมไม่หาย ได้ค่า 0 = ยังไม่ให้คะแนน
        if (oldVersion < 2) db.execSQL("ALTER TABLE runs ADD COLUMN feel INTEGER NOT NULL DEFAULT 0")
    }

    companion object {
        const val NAME = "runtrail.db"
        const val VERSION = 2
    }
}

package com.example.runtrail.data

import java.util.TimeZone
import java.util.UUID

/**
 * โมเดลการวิ่ง 1 รอบ (MA10) — เก็บในตาราง runs ของ SQLite (RunDb)
 *
 * - id: คีย์ในเครื่อง (ใช้ส่งผ่าน Intent)
 * - uuid: สร้างในเครื่องตอนเริ่มวิ่ง ใช้เป็นรหัสบน server → ส่งซ้ำกี่ครั้งก็ไม่เกิดข้อมูลซ้ำ
 * - track: จุด GPS ทั้งหมด โหลดเฉพาะหน้าที่ต้องใช้ (สรุปผล / กราฟ / Replay)
 * - thumb: เส้นทางย่อ ~120 จุด สำหรับการ์ดในรายการ (ไม่ต้องโหลดจุดทั้งหมด)
 */
data class Run(
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val ownerUserId: String? = null,         // null = ยังไม่ล็อกอิน (Guest) จะถูกผูกบัญชีตอนล็อกอิน
    val source: RunSource = RunSource.GPS,
    val title: String,
    val note: String? = null,
    val startTime: Long,                      // epoch millis
    val endTime: Long? = null,                // null = ยังวิ่งไม่จบ (draft ใช้กู้คืน)
    val timezone: String? = TimeZone.getDefault().id,
    val distanceM: Double = 0.0,
    val movingTimeS: Long = 0,
    val calories: Double = 0.0,
    val elevationGainM: Int = 0,
    val cadenceSpm: Int = 0,
    val tempC: Int? = null,                   // null = เครื่องไม่มีเซนเซอร์อุณหภูมิ
    val photoUri: String? = null,             // path รูปในเครื่อง
    val photoSync: PhotoSync = PhotoSync.NONE,
    val shareCode: String? = null,
    val shareUrl: String? = null,
    val shareHideEnds: Boolean = true,
    val shareShowPhoto: Boolean = true,
    val shareShowKcal: Boolean = false,
    val verified: Boolean? = null,            // null = server ยังไม่ได้ตรวจ
    val verifyReason: String? = null,
    val serverDistanceM: Double? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncError: String? = null,
    val onServer: Boolean = false,            // เคยขึ้น server สำเร็จอย่างน้อย 1 ครั้ง
    val updatedAt: Long = System.currentTimeMillis(), // = client_updated_at (last-write-wins)
    val deleted: Boolean = false,             // รอส่งคำสั่งลบไป server
    val feel: Int = 0,                        // ความรู้สึกหลังวิ่ง 1–5 ดาว (0 = ไม่ได้ให้) · เก็บในเครื่อง ไม่ส่ง server
    val track: List<TrackPoint> = emptyList(),
    val thumb: List<TrackPoint> = emptyList(),
    val pauses: List<Pause> = emptyList(),
    val splits: List<Split> = emptyList(),
) {
    val isGps: Boolean get() = source == RunSource.GPS
    val finished: Boolean get() = endTime != null
}

/** จุด GPS 1 จุด (ตาราง run_points) */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val time: Long,          // epoch millis
    val altitude: Double,
    val accuracy: Float,
)

/** ช่วงที่หยุดวิ่ง (กดหยุดเอง หรือ auto-pause) — ระยะในช่วงนี้ไม่นับ ทั้งในแอปและบน server */
data class Pause(val start: Long, val end: Long)

enum class RunSource(val api: String) {
    GPS("gps"), MANUAL("manual");

    companion object {
        fun from(api: String?) = entries.firstOrNull { it.api == api } ?: GPS
    }
}

/** สถานะซิงก์กับเซิร์ฟเวอร์ (MA11) */
enum class SyncStatus { SYNCED, PENDING, FAILED }

/** สถานะรูปของการวิ่งบน S3 */
enum class PhotoSync { NONE, PENDING, SYNCED, DELETE }

/** ข้อมูลรายกิโลเมตร (ตาราง splits) */
data class Split(
    val km: Int,
    val paceSecPerKm: Int,
    val elevationDeltaM: Int,
)

/** จุดบนเส้นทาง (พิกัดแบบ normalized 0..1 สำหรับวาดภาพย่อ/กราฟ) */
data class RoutePoint(
    val x: Float,
    val y: Float,
    val paceSecPerKm: Int,
    val altitudeM: Float,
)

/** สถิติดีที่สุด */
data class PersonalRecord(
    val title: String,
    val value: String,
    val dateMillis: Long,
    val isNew: Boolean,
)

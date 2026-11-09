package com.example.runtrail.data

/**
 * โมเดลการวิ่ง 1 รอบ (MA10) — ตอนนี้เก็บในหน่วยความจำผ่าน RunRepository
 * ภายหลังจะย้ายไปเก็บในตาราง runs ของ SQLite ด้วยฟิลด์ชุดเดียวกัน
 */
data class Run(
    val id: Long = 0,
    val title: String,
    val note: String? = null,
    val startTime: Long,                 // epoch millis
    val endTime: Long? = null,           // null = ยังวิ่งไม่จบ (ใช้กู้การวิ่งที่ค้าง)
    val distanceM: Double = 0.0,
    val movingTimeS: Long = 0,
    val calories: Double = 0.0,
    val elevationGainM: Int = 0,
    val cadenceSpm: Int = 0,
    val tempC: Int = 29,
    val humidity: Int = 68,
    val photoUri: String? = null,
    val serverId: Long? = null,
    val shareCode: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val routeSeed: Int = 1,
    val track: List<TrackPoint> = emptyList(),   // จุด GPS จริง (ว่าง = ข้อมูลตัวอย่าง)
)

/** จุด GPS 1 จุด (ตาราง run_points) */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val time: Long,          // epoch millis
    val altitude: Double,
    val accuracy: Float,
)

/** สถานะซิงก์กับเซิร์ฟเวอร์ (MA11) */
enum class SyncStatus { SYNCED, PENDING, FAILED }

/** ข้อมูลรายกิโลเมตร (ตาราง splits) */
data class Split(
    val km: Int,
    val paceSecPerKm: Int,
    val elevationDeltaM: Int,
)

/** จุดบนเส้นทาง (พิกัดแบบ normalized 0..1 สำหรับวาดบนแผนที่จำลอง) */
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

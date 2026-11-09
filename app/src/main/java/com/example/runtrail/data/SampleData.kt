package com.example.runtrail.data

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * ข้อมูลตัวอย่างสำหรับช่วงทำ UI (ยังไม่มี backend/SQLite)
 * ตัวเลขชุดล่าสุดตรงกับหน้าจอใน Figma: 7 วันล่าสุด 12.4 / 20 กม. · 3 ครั้ง · 1:18 ชม. · 812 kcal
 * วันที่อิงจาก "วันนี้" เพื่อให้หน้าหลักมีข้อมูลเสมอไม่ว่าจะเปิดแอปวันไหน
 */
object SampleData {

    fun runs(): List<Run> {
        val list = mutableListOf<Run>()
        var id = 1L

        // ---- การวิ่งเดือน พ.ย. 2026 (ตรงกับการ์ดใน Figma) ----
        list += Run(
            id = id++, title = "วิ่งเย็นริมสวน", note = "ลมเย็นดี วิ่งรอบสวนสองรอบ",
            startTime = daysAgo(1, 17, 42), distanceM = 4300.0, movingTimeS = 27 * 60 + 5,
            calories = 282.0, elevationGainM = 14, cadenceSpm = 166, tempC = 30, humidity = 64,
            syncStatus = SyncStatus.SYNCED, serverId = 41, shareCode = "H3W8P1", routeSeed = 2,
        )
        list += Run(
            id = id++, title = "วิ่งเช้าวันพฤหัส", note = "อากาศเย็น เพซดีกว่าที่คิด",
            startTime = daysAgo(3, 6, 10), distanceM = 5000.0, movingTimeS = 29 * 60 + 40,
            calories = 330.0, elevationGainM = 18, cadenceSpm = 172, tempC = 26, humidity = 78,
            syncStatus = SyncStatus.PENDING, routeSeed = 3,
        )
        list += Run(
            id = id++, title = "วิ่งเบา ๆ ก่อนเรียน", note = null,
            startTime = daysAgo(5, 6, 30), distanceM = 3100.0, movingTimeS = 21 * 60 + 15,
            calories = 200.0, elevationGainM = 6, cadenceSpm = 162, tempC = 27, humidity = 74,
            syncStatus = SyncStatus.SYNCED, serverId = 40, shareCode = "M2C7Q4", routeSeed = 4,
        )
        list += Run(
            id = id++, title = "Long run สวนรถไฟ", note = "ซ้อมยาวรอบสวนรถไฟ ดื่มน้ำที่กม. 5",
            startTime = daysAgo(7, 6, 0), distanceM = 10200.0, movingTimeS = 63 * 60 + 55,
            calories = 660.0, elevationGainM = 41, cadenceSpm = 170, tempC = 27, humidity = 71,
            syncStatus = SyncStatus.FAILED, routeSeed = 5,
        )

        // ---- ย้อนหลัง พ.ค. – ต.ค. (กราฟรายเดือนในหน้าสถิติ) ----
        val months = listOf(
            Triple(6, 20.0, 4),   // ย้อนหลังกี่เดือน, ระยะรวม (กม.), จำนวนครั้ง
            Triple(5, 38.0, 7),
            Triple(4, 52.0, 8),
            Triple(3, 44.0, 7),
            Triple(2, 61.0, 8),
            Triple(1, 75.0, 10),
        )
        val titles = listOf("วิ่งเช้า", "วิ่งเย็นริมสวน", "วิ่งเบา ๆ", "วิ่งสะสมระยะ", "วิ่งรอบหมู่บ้าน")
        val weights = listOf(0.82, 1.12, 0.94, 1.2, 0.88, 1.05, 0.9, 1.15, 0.97, 1.0)
        for ((monthsBack, totalKm, count) in months) {
            val month = monthsBack
            val fixed = mutableListOf<Run>()
            if (monthsBack == 1) {
                fixed += Run(
                    id = 0, title = "วิ่งเบา ๆ", startTime = daysAgo(9, 18, 5),
                    distanceM = 3100.0, movingTimeS = 19 * 60 + 30, calories = 200.0,
                    elevationGainM = 5, cadenceSpm = 164, syncStatus = SyncStatus.SYNCED,
                    serverId = 39, shareCode = "Z8K1L5", routeSeed = 6,
                )
                fixed += Run(
                    id = 0, title = "Long run สวนรถไฟ", startTime = monthsAgo(1, 19, 6, 0),
                    distanceM = 12300.0, movingTimeS = 76 * 60 + 40, calories = 800.0,
                    elevationGainM = 47, cadenceSpm = 168, syncStatus = SyncStatus.SYNCED,
                    serverId = 33, shareCode = "R5T9B2", routeSeed = 7,
                )
            }
            val restKm = totalKm - fixed.sumOf { it.distanceM } / 1000.0
            val restCount = count - fixed.size
            val w = weights.take(restCount)
            val wSum = w.sum()
            var used = 0.0
            for (i in 0 until restCount) {
                val km = if (i == restCount - 1) {
                    ((restKm - used) * 10).roundToInt() / 10.0
                } else {
                    ((restKm * w[i] / wSum) * 10).roundToInt() / 10.0
                }
                used += km
                val day = 2 + i * (26 / restCount.coerceAtLeast(1))
                val morning = i % 2 == 0
                val pace = 350 + ((i * 37 + month * 11) % 45)          // 5:50 – 6:35 /กม.
                fixed += Run(
                    id = 0,
                    title = if (km >= 8.0) "Long run สวนรถไฟ" else titles[(i + month) % titles.size],
                    startTime = monthsAgo(monthsBack, day.coerceAtMost(28), if (morning) 6 else 17, if (morning) 5 else 35),
                    distanceM = km * 1000.0,
                    movingTimeS = (km * pace).toLong(),
                    calories = (km * 65.0).roundToInt().toDouble(),
                    elevationGainM = (km * 4).roundToInt(),
                    cadenceSpm = 160 + (i * 3 + month) % 14,
                    syncStatus = SyncStatus.SYNCED,
                    serverId = (month * 10 + i).toLong(),
                    shareCode = "S${month}${i}Q",
                    routeSeed = 10 + month * 10 + i,
                )
            }
            fixed.sortedByDescending { it.startTime }.forEach { list += it.copy(id = id++) }
        }
        return list.sortedByDescending { it.startTime }
    }

    /** splits ตัวอย่างของการวิ่งที่ใช้ใน Figma (5.02 กม.) */
    val figmaSplits = listOf(
        Split(1, 6 * 60 + 5, 4),
        Split(2, 6 * 60 + 10, 6),
        Split(3, 6 * 60 + 31, 9),
        Split(4, 5 * 60 + 48, 3),
        Split(5, 6 * 60 + 12, 2),
    )

    /** n วันก่อน เวลา hh:mm */
    fun daysAgo(days: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -days)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** n เดือนก่อน วันที่ day เวลา hh:mm */
    fun monthsAgo(months: Int, day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        add(Calendar.MONTH, -months)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}

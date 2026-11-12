package com.example.runtrail.domain

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * การแปลงหน่วยที่ใช้ร่วมกันหลายหน้าจอ (Kotlin ล้วน ทดสอบบน JVM ได้)
 *
 * เป้าหมายรายสัปดาห์เก็บเป็น "กม." เสมอ (ตรงกับ weekly_goal_km บน server) — โหมดไมล์แค่แสดงผลเป็นไมล์
 */
object Units {

    /** ระยะเป็นหน่วยที่ผู้ใช้เลือก (กม. หรือ ไมล์) */
    fun inUnit(meters: Double, miles: Boolean): Double =
        if (miles) meters / 1000.0 / Fmt.KM_PER_MILE else meters / 1000.0

    /** เป้าหมายรายสัปดาห์เป็นเมตร (ค่าเก็บเป็น กม. ไม่ว่าเลือกหน่วยไหน) */
    fun goalMeters(goalKm: Int): Double = goalKm * 1000.0

    /** ตัวเลขเป้าหมายตามหน่วยที่แสดง: กม. → "20" · ไมล์ → "12.4" */
    fun goalText(goalKm: Int, miles: Boolean): String =
        if (!miles) goalKm.toString()
        else String.format(Locale.US, "%.1f", inUnit(goalMeters(goalKm), true))

    /** เปอร์เซ็นต์ของเป้าที่ทำได้ (0 ถ้าไม่มีเป้า) */
    fun goalPercent(doneMeters: Double, goalKm: Int): Int {
        val goal = goalMeters(goalKm)
        return if (goal <= 0) 0 else (doneMeters / goal * 100).roundToInt()
    }

    /**
     * ตำแหน่งตัวเลขบนแกน X ของกราฟ (0 … N ตามหน่วยที่เลือก) ไม่เกินประมาณ 6 ช่อง
     * เดิมคำนวณเป็นกิโลเมตรเสมอแต่เขียนหน่วยเป็นไมล์ → ตัวเลขผิดในโหมดไมล์
     */
    fun axisMarks(meters: Double, miles: Boolean): List<Int> {
        val total = ceil(inUnit(meters, miles) - 1e-9).toInt().coerceAtLeast(1)
        val step = if (total > 6) ceil(total / 5.0).toInt() else 1
        return (0..total step step).toMutableList().apply { if (last() != total) add(total) }
    }
}

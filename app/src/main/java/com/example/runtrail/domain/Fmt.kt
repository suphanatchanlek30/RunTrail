package com.example.runtrail.domain

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * แปลงตัวเลขเป็นข้อความแสดงผล (Kotlin ล้วน ทดสอบบน JVM ได้)
 */
object Fmt {

    private val DAY_SHORT = arrayOf("อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.")
    private val DAY_FULL = arrayOf("อาทิตย์", "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์")
    private val MONTH_SHORT = arrayOf(
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.",
    )
    private val MONTH_FULL = arrayOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม",
    )
    val WEEK_LETTERS = arrayOf("จ", "อ", "พ", "พฤ", "ศ", "ส", "อา")

    const val KM_PER_MILE = 1.609344

    /** ระยะทางตามหน่วยที่เลือก เช่น 4.62 */
    fun distance(meters: Double, miles: Boolean = false, decimals: Int = 2): String {
        val v = if (miles) meters / 1000.0 / KM_PER_MILE else meters / 1000.0
        return String.format(Locale.US, "%.${decimals}f", v)
    }

    fun unit(miles: Boolean): String = if (miles) "ไมล์" else "กม."

    fun paceUnit(miles: Boolean): String = if (miles) "/ไมล์" else "/กม."

    /** 28:41 หรือ 1:03:55 */
    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** แบบย่อสำหรับการ์ด: 27:05 หรือ 1:04 (ชม.:นาที) */
    fun durationShort(seconds: Long): String {
        val h = seconds / 3600
        return if (h > 0) {
            val m = ((seconds % 3600) / 60.0).roundToInt()
            String.format(Locale.US, "%d:%02d", h, m)
        } else duration(seconds)
    }

    /** ชั่วโมงรวม เช่น 1:18 */
    fun hours(seconds: Long): String {
        val totalMin = (seconds / 60.0).roundToInt()
        return String.format(Locale.US, "%d:%02d", totalMin / 60, totalMin % 60)
    }

    /** เพซ วินาที/กม. → 6:12 */
    fun pace(secPerKm: Double, miles: Boolean = false): String {
        if (secPerKm <= 0 || secPerKm.isInfinite() || secPerKm.isNaN()) return "--:--"
        val v = if (miles) secPerKm * KM_PER_MILE else secPerKm
        val total = v.roundToInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    fun pace(secPerKm: Int): String = pace(secPerKm.toDouble())

    fun number(v: Double): String = String.format(Locale.US, "%,d", v.roundToInt())

    /** 21.8k */
    fun compact(v: Double): String =
        if (v >= 10_000) String.format(Locale.US, "%.1fk", v / 1000.0) else number(v)

    /** ส. 8 พ.ย. · 17:42 */
    fun cardDate(millis: Long): String {
        val c = cal(millis)
        return "${DAY_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} " +
            "${MONTH_SHORT[c.get(Calendar.MONTH)]} · ${time(millis)}"
    }

    /** อา. 9 พ.ย. 2026 · 17:42 – 18:15 */
    fun detailDate(start: Long, end: Long?): String {
        val c = cal(start)
        val base = "${DAY_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} " +
            "${MONTH_SHORT[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)} · ${time(start)}"
        return if (end != null) "$base – ${time(end)}" else base
    }

    /** อาทิตย์ 9 พฤศจิกายน 2026 */
    fun longDate(millis: Long): String {
        val c = cal(millis)
        return "${DAY_FULL[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} " +
            "${MONTH_FULL[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** 9 พ.ย. 2026 */
    fun shortDate(millis: Long): String {
        val c = cal(millis)
        return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTH_SHORT[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** 3 – 9 พ.ย. */
    fun weekRange(weekStart: Long): String {
        val s = cal(weekStart)
        val e = cal(weekStart + 6L * 24 * 60 * 60 * 1000)
        return if (s.get(Calendar.MONTH) == e.get(Calendar.MONTH)) {
            "${s.get(Calendar.DAY_OF_MONTH)} – ${e.get(Calendar.DAY_OF_MONTH)} ${MONTH_SHORT[e.get(Calendar.MONTH)]}"
        } else {
            "${s.get(Calendar.DAY_OF_MONTH)} ${MONTH_SHORT[s.get(Calendar.MONTH)]} – " +
                "${e.get(Calendar.DAY_OF_MONTH)} ${MONTH_SHORT[e.get(Calendar.MONTH)]}"
        }
    }

    fun monthYear(millis: Long): String {
        val c = cal(millis)
        return "${MONTH_FULL[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    fun monthShort(month: Int): String = MONTH_SHORT[month]

    fun time(millis: Long): String {
        val c = cal(millis)
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** คำทักทายตามช่วงเวลา */
    fun greeting(millis: Long = System.currentTimeMillis()): String =
        when (cal(millis).get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "สวัสดีตอนเช้า"
            in 12..15 -> "สวัสดีตอนบ่าย"
            in 16..19 -> "สวัสดีตอนเย็น"
            else -> "สวัสดีตอนค่ำ"
        }

    private fun cal(millis: Long): Calendar = Calendar.getInstance().apply { timeInMillis = millis }
}

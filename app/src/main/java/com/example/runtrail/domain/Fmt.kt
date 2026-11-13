package com.example.runtrail.domain

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Formats numbers, durations and dates for display in the current UI language. */
object Fmt {

    private val DAY_SHORT_TH = arrayOf("อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.")
    private val DAY_SHORT_EN = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val DAY_FULL_TH = arrayOf("อาทิตย์", "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์")
    private val DAY_FULL_EN = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTH_SHORT_TH = arrayOf(
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.",
    )
    private val MONTH_SHORT_EN = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    private val MONTH_FULL_TH = arrayOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม",
    )
    private val MONTH_FULL_EN = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val DAY_SHORT get() = if (Lang.english()) DAY_SHORT_EN else DAY_SHORT_TH
    private val DAY_FULL get() = if (Lang.english()) DAY_FULL_EN else DAY_FULL_TH
    private val MONTH_SHORT get() = if (Lang.english()) MONTH_SHORT_EN else MONTH_SHORT_TH
    private val MONTH_FULL get() = if (Lang.english()) MONTH_FULL_EN else MONTH_FULL_TH

    /** One-letter weekday labels, Monday first. */
    val WEEK_LETTERS: Array<String>
        get() = if (Lang.english()) arrayOf("M", "T", "W", "T", "F", "S", "S")
        else arrayOf("จ", "อ", "พ", "พฤ", "ศ", "ส", "อา")

    /** One-letter weekday labels, Sunday first (index = Calendar.DAY_OF_WEEK - 1). */
    val DAY_LETTERS: Array<String>
        get() = if (Lang.english()) arrayOf("S", "M", "T", "W", "T", "F", "S")
        else arrayOf("อา", "จ", "อ", "พ", "พฤ", "ศ", "ส")

    const val KM_PER_MILE = 1.609344

    /** Short weekday name, e.g. "Mon", for a Calendar.DAY_OF_WEEK value. */
    fun dayShort(dayOfWeek: Int): String = DAY_SHORT[dayOfWeek - 1]

    /** Reminder summary, e.g. "18:00 · Every day" or "06:30 · Mon Wed Fri". */
    fun reminderSummary(hour: Int, minute: Int, daysMask: Int): String {
        val time = String.format(Locale.US, "%02d:%02d", hour, minute)
        val mondayFirst = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val weekdays = mondayFirst.take(5).sumOf { ReminderTime.bit(it) }
        val days = when (daysMask and ReminderTime.ALL_DAYS) {
            ReminderTime.ALL_DAYS -> Lang.t("ทุกวัน", "Every day")
            weekdays -> Lang.t("จ.–ศ.", "Weekdays")
            else -> mondayFirst.filter { ReminderTime.has(daysMask, it) }.joinToString(" ") { dayShort(it) }
        }
        return "$time · $days"
    }

    /** Distance in km or miles, e.g. "4.62". */
    fun distance(meters: Double, miles: Boolean = false, decimals: Int = 2): String {
        val v = if (miles) meters / 1000.0 / KM_PER_MILE else meters / 1000.0
        return String.format(Locale.US, "%.${decimals}f", v)
    }

    fun unit(miles: Boolean): String = if (miles) Lang.t("ไมล์", "mi") else Lang.t("กม.", "km")

    fun paceUnit(miles: Boolean): String = "/" + unit(miles)

    /** "28:41" or "1:03:55". */
    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Compact duration for cards: "27:05" (m:ss) or "1:04" (h:mm). */
    fun durationShort(seconds: Long): String {
        val h = seconds / 3600
        return if (h > 0) {
            val m = ((seconds % 3600) / 60.0).roundToInt()
            String.format(Locale.US, "%d:%02d", h, m)
        } else duration(seconds)
    }

    /** Total time as h:mm, e.g. "1:18". */
    fun hours(seconds: Long): String {
        val totalMin = (seconds / 60.0).roundToInt()
        return String.format(Locale.US, "%d:%02d", totalMin / 60, totalMin % 60)
    }

    /** Pace as m:ss per km or per mile, e.g. "6:12". */
    fun pace(secPerKm: Double, miles: Boolean = false): String {
        if (secPerKm <= 0 || secPerKm.isInfinite() || secPerKm.isNaN()) return "--:--"
        val v = if (miles) secPerKm * KM_PER_MILE else secPerKm
        val total = v.roundToInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    fun pace(secPerKm: Int): String = pace(secPerKm.toDouble())

    fun number(v: Double): String = String.format(Locale.US, "%,d", v.roundToInt())

    /** Thousands as "21.8k". */
    fun compact(v: Double): String =
        if (v >= 10_000) String.format(Locale.US, "%.1fk", v / 1000.0) else number(v)

    /** "Sat 8 Nov · 17:42" */
    fun cardDate(millis: Long): String {
        val c = cal(millis)
        return "${DAY_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} " +
            "${MONTH_SHORT[c.get(Calendar.MONTH)]} · ${time(millis)}"
    }

    /** "Sun 9 Nov 2026 · 17:42 – 18:15" */
    fun detailDate(start: Long, end: Long?): String {
        val c = cal(start)
        val base = "${DAY_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} " +
            "${MONTH_SHORT[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)} · ${time(start)}"
        return if (end != null) "$base – ${time(end)}" else base
    }

    /** "Sunday 9 November 2026" */
    fun longDate(millis: Long): String {
        val c = cal(millis)
        return "${DAY_FULL[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} " +
            "${MONTH_FULL[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** "9 Nov 2026" */
    fun shortDate(millis: Long): String {
        val c = cal(millis)
        return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTH_SHORT[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** "3 – 9 Nov" */
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

    /** Greeting for the time of day. */
    fun greeting(millis: Long = System.currentTimeMillis()): String =
        when (cal(millis).get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> Lang.t("สวัสดีตอนเช้า", "Good morning")
            in 12..15 -> Lang.t("สวัสดีตอนบ่าย", "Good afternoon")
            in 16..19 -> Lang.t("สวัสดีตอนเย็น", "Good evening")
            else -> Lang.t("สวัสดีตอนค่ำ", "Good night")
        }

    private fun cal(millis: Long): Calendar = Calendar.getInstance().apply { timeInMillis = millis }
}

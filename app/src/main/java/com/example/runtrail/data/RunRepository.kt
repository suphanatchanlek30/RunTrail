package com.example.runtrail.data

import com.example.runtrail.domain.RouteGenerator
import java.util.Calendar

/**
 * แหล่งข้อมูลการวิ่ง (ช่วง UI: เก็บในหน่วยความจำ)
 * ชื่อเมธอดตั้งให้ตรงกับ DBHelper ของ MA10 (getAll / getById / insert / update / delete)
 * เมื่อทำฝั่ง SQLite จะเปลี่ยนแค่ข้างในคลาสนี้ หน้าจอไม่ต้องแก้
 */
object RunRepository {

    enum class Filter { ALL, THIS_WEEK, THIS_MONTH, OVER_5K }
    enum class Sort { NEWEST, OLDEST, LONGEST, FASTEST }

    private val runs: MutableList<Run> = SampleData.runs().toMutableList()

    fun getAll(): List<Run> = runs.sortedByDescending { it.startTime }

    fun getById(id: Long): Run? = runs.firstOrNull { it.id == id }

    fun insert(run: Run): Long {
        val newId = (runs.maxOfOrNull { it.id } ?: 0L) + 1
        runs += run.copy(id = newId)
        return newId
    }

    fun update(run: Run): Int {
        val index = runs.indexOfFirst { it.id == run.id }
        if (index < 0) return 0
        runs[index] = run
        return 1
    }

    fun delete(id: Long): Int = if (runs.removeAll { it.id == id }) 1 else 0

    /** ค้นหา + กรอง + เรียง (แทน WHERE / ORDER BY ของ SQLite) */
    fun query(text: String, filter: Filter, sort: Sort): List<Run> {
        val now = System.currentTimeMillis()
        val weekStart = startOfWeek(now)
        val monthStart = startOfMonth(now)
        val q = text.trim()
        val filtered = runs.filter { run ->
            val matchText = q.isEmpty() ||
                run.title.contains(q, ignoreCase = true) ||
                (run.note?.contains(q, ignoreCase = true) ?: false)
            val matchFilter = when (filter) {
                Filter.ALL -> true
                Filter.THIS_WEEK -> run.startTime >= weekStart
                Filter.THIS_MONTH -> run.startTime >= monthStart
                Filter.OVER_5K -> run.distanceM > 5000.0
            }
            matchText && matchFilter
        }
        return when (sort) {
            Sort.NEWEST -> filtered.sortedByDescending { it.startTime }
            Sort.OLDEST -> filtered.sortedBy { it.startTime }
            Sort.LONGEST -> filtered.sortedByDescending { it.distanceM }
            Sort.FASTEST -> filtered.sortedBy { if (it.distanceM > 0) it.movingTimeS / it.distanceM else Double.MAX_VALUE }
        }
    }

    /** การวิ่งใน 7 วันล่าสุด (รวมวันนี้) — ตรงกับ "กิจกรรม 7 วัน" ในหน้าหลัก */
    fun thisWeek(now: Long = System.currentTimeMillis()): List<Run> {
        val start = startOfWeek(now)
        return runs.filter { it.startTime >= start && it.startTime <= now }
    }

    /** ระยะทางรายวัน 7 วันล่าสุด (index 6 = วันนี้) หน่วย กม. */
    fun weekDailyKm(now: Long = System.currentTimeMillis()): FloatArray {
        val start = startOfWeek(now)
        val out = FloatArray(7)
        thisWeek(now).forEach { run ->
            val day = ((run.startTime - start) / DAY_MS).toInt().coerceIn(0, 6)
            out[day] += (run.distanceM / 1000.0).toFloat()
        }
        return out
    }

    /** ตัวย่อวันของ 7 วันล่าสุด เช่น อ พ พฤ ศ ส อา จ */
    fun weekDayLetters(now: Long = System.currentTimeMillis()): List<String> {
        val letters = arrayOf("อา", "จ", "อ", "พ", "พฤ", "ศ", "ส")
        val start = startOfWeek(now)
        return List(7) { i ->
            val c = Calendar.getInstance().apply { timeInMillis = start + i * DAY_MS }
            letters[c.get(Calendar.DAY_OF_WEEK) - 1]
        }
    }

    /** ระยะทางรวมรายเดือนย้อนหลัง n เดือน (เก่า → ใหม่) */
    fun monthlyKm(months: Int = 6, now: Long = System.currentTimeMillis()): List<Pair<Int, Float>> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val result = mutableListOf<Pair<Int, Float>>()
        for (i in months - 1 downTo 0) {
            val c = cal.clone() as Calendar
            c.add(Calendar.MONTH, -i)
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH)
            val km = runs.filter {
                val rc = Calendar.getInstance().apply { timeInMillis = it.startTime }
                rc.get(Calendar.YEAR) == y && rc.get(Calendar.MONTH) == m
            }.sumOf { it.distanceM } / 1000.0
            result += m to km.toFloat()
        }
        return result
    }

    fun splits(run: Run): List<Split> = RouteGenerator.splits(run)

    fun route(run: Run): List<RoutePoint> = RouteGenerator.route(run)

    fun pendingCount(): Int = runs.count { it.syncStatus != SyncStatus.SYNCED }

    /** เที่ยงคืนของ 6 วันก่อน (ช่วง 7 วันล่าสุด) */
    fun startOfWeek(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -6)
    }.timeInMillis

    fun startOfMonth(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WEEK_MS = 7 * DAY_MS
}

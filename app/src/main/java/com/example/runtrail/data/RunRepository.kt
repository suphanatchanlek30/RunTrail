package com.example.runtrail.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import com.example.runtrail.domain.Codec
import com.example.runtrail.domain.Periods
import com.example.runtrail.domain.RouteGenerator
import com.example.runtrail.domain.TrackMath
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

/**
 * แหล่งข้อมูลการวิ่ง (MA10) — อ่าน/เขียน SQLite ทั้งหมด ใช้ได้แม้ไม่มีเน็ต
 * ชื่อเมธอดเดิม (getAll / getById / insert / update / delete / query) ยังเหมือนเดิม หน้าจอจึงแก้น้อยที่สุด
 *
 * การวิ่งที่ "มองเห็น" = จบแล้ว, ไม่ถูกลบ และเป็นของผู้ใช้ปัจจุบันหรือของ Guest (owner ว่าง)
 */
object RunRepository {

    enum class Filter { ALL, THIS_WEEK, THIS_MONTH, OVER_5K }
    enum class Sort { NEWEST, OLDEST, LONGEST, FASTEST }

    private lateinit var helper: RunDb
    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** ผู้ใช้ที่ล็อกอินอยู่ (ตั้งจาก App) — null = Guest */
    @Volatile var currentUser: () -> String? = { null }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context, database: RunDb = RunDb(context.applicationContext)) {
        if (::helper.isInitialized) helper.close()
        helper = database
    }

    /** หน้าจอที่เปิดอยู่ลงทะเบียนไว้ เพื่อรีเฟรชเมื่อการซิงก์เบื้องหลังเปลี่ยนข้อมูล */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyChanged() { main.post { listeners.forEach { it() } } }

    // ---------------------------------------------------------------- อ่าน

    private fun visibleWhere(): Pair<String, Array<String>> {
        val user = currentUser()
        return if (user == null) {
            "end_time IS NOT NULL AND deleted = 0 AND owner IS NULL" to emptyArray()
        } else {
            "end_time IS NOT NULL AND deleted = 0 AND (owner IS NULL OR owner = ?)" to arrayOf(user)
        }
    }

    /** การวิ่งทั้งหมด (ไม่มีจุด GPS เต็ม แต่มีเส้นทางย่อและ splits) เรียงใหม่ → เก่า */
    fun getAll(): List<Run> {
        val (where, args) = visibleWhere()
        return queryRuns(where, args, "start_time DESC", withTrack = false)
    }

    fun getById(id: Long, withTrack: Boolean = true): Run? =
        queryRuns("id = ?", arrayOf(id.toString()), null, withTrack).firstOrNull()

    fun getByUuid(uuid: String, withTrack: Boolean = false): Run? =
        queryRuns("uuid = ?", arrayOf(uuid), null, withTrack).firstOrNull()

    private fun queryRuns(where: String, args: Array<String>, order: String?, withTrack: Boolean): List<Run> {
        val out = mutableListOf<Run>()
        db.query("runs", null, where, args, null, null, order).use { c ->
            while (c.moveToNext()) out += readRun(c)
        }
        if (out.isEmpty()) return out
        val splits = loadSplits(out.map { it.id })
        return out.map { r ->
            r.copy(splits = splits[r.id].orEmpty(), track = if (withTrack) loadTrack(r.id) else emptyList())
        }
    }

    private fun readRun(c: Cursor): Run {
        fun str(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun long(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        fun longOrNull(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
        fun dbl(n: String) = c.getDouble(c.getColumnIndexOrThrow(n))
        fun dblOrNull(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getDouble(it) }
        fun bool(n: String) = long(n) != 0L
        return Run(
            id = long("id"), uuid = str("uuid")!!, ownerUserId = str("owner"),
            source = RunSource.valueOf(str("source")!!), title = str("title")!!, note = str("note"),
            startTime = long("start_time"), endTime = longOrNull("end_time"), timezone = str("timezone"),
            distanceM = dbl("distance_m"), movingTimeS = long("moving_time_s"), calories = dbl("calories"),
            elevationGainM = long("elevation_gain_m").toInt(), cadenceSpm = long("cadence_spm").toInt(),
            tempC = longOrNull("temp_c")?.toInt(), photoUri = str("photo_path"),
            photoSync = PhotoSync.valueOf(str("photo_sync")!!), shareCode = str("share_code"), shareUrl = str("share_url"),
            shareHideEnds = bool("share_hide_ends"), shareShowPhoto = bool("share_show_photo"),
            shareShowKcal = bool("share_show_kcal"), verified = longOrNull("verified")?.let { it != 0L },
            verifyReason = str("verify_reason"), serverDistanceM = dblOrNull("server_distance_m"),
            syncStatus = SyncStatus.valueOf(str("sync_status")!!), syncError = str("sync_error"),
            onServer = bool("on_server"), updatedAt = long("updated_at"), deleted = bool("deleted"),
            thumb = Codec.decodeTrack(str("thumb")), pauses = Codec.decodePauses(str("pauses")),
        )
    }

    fun loadTrack(runId: Long): List<TrackPoint> {
        val out = mutableListOf<TrackPoint>()
        db.query("run_points", arrayOf("lat", "lng", "time", "alt", "acc"), "run_id = ?", arrayOf(runId.toString()),
            null, null, "seq").use { c ->
            while (c.moveToNext()) out += TrackPoint(c.getDouble(0), c.getDouble(1), c.getLong(2), c.getDouble(3), c.getFloat(4))
        }
        return out
    }

    private fun loadSplits(ids: List<Long>): Map<Long, List<Split>> {
        val out = HashMap<Long, MutableList<Split>>()
        ids.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT run_id, km, pace_s, elev_delta_m FROM splits WHERE run_id IN ($marks) ORDER BY km",
                chunk.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getLong(0)) { mutableListOf() } += Split(c.getInt(1), c.getInt(2), c.getInt(3))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- เขียน

    private fun values(r: Run) = ContentValues().apply {
        put("uuid", r.uuid); put("owner", r.ownerUserId); put("source", r.source.name)
        put("title", r.title); put("note", r.note); put("start_time", r.startTime); put("end_time", r.endTime)
        put("timezone", r.timezone); put("distance_m", r.distanceM); put("moving_time_s", r.movingTimeS)
        put("calories", r.calories); put("elevation_gain_m", r.elevationGainM); put("cadence_spm", r.cadenceSpm)
        put("temp_c", r.tempC); put("photo_path", r.photoUri); put("photo_sync", r.photoSync.name)
        put("share_code", r.shareCode); put("share_url", r.shareUrl)
        put("share_hide_ends", r.shareHideEnds); put("share_show_photo", r.shareShowPhoto); put("share_show_kcal", r.shareShowKcal)
        put("verified", r.verified); put("verify_reason", r.verifyReason); put("server_distance_m", r.serverDistanceM)
        put("sync_status", r.syncStatus.name); put("sync_error", r.syncError); put("on_server", r.onServer)
        put("updated_at", r.updatedAt); put("deleted", r.deleted)
        put("pauses", Codec.encodePauses(r.pauses))
    }

    /** เพิ่มการวิ่งใหม่ (จบแล้ว) พร้อมจุด GPS · คำนวณ splits และเส้นทางย่อให้ */
    fun insert(run: Run): Long {
        var id = 0L
        db.transaction {
            val r = run.copy(ownerUserId = run.ownerUserId ?: currentUser())
            id = insertOrThrow("runs", null, values(r))
            writeTrackDerived(this, id, r.track)
            writePoints(this, id, 0, r.track)
        }
        notifyChanged()
        return id
    }

    /**
     * แก้ไขข้อมูลการวิ่ง (ไม่แตะจุด GPS) — ถ้า touch = true ถือเป็นการแก้ของผู้ใช้:
     * เวลาอัปเดตเป็นปัจจุบัน และรอซิงก์ใหม่
     */
    fun update(run: Run, touch: Boolean = true): Int {
        val r = if (touch) run.copy(updatedAt = maxOf(System.currentTimeMillis(), run.updatedAt + 1),
            syncStatus = SyncStatus.PENDING, syncError = null) else run
        val n = db.update("runs", values(r), "id = ?", arrayOf(r.id.toString()))
        if (n > 0) notifyChanged()
        return n
    }

    /**
     * ลบการวิ่ง — ถ้าเคยขึ้น server และ alsoServer = true จะเก็บเป็น "รอลบ" ให้ SyncEngine ส่งคำสั่งลบ
     * ไม่อย่างนั้นลบออกจากเครื่องทันที
     */
    fun delete(id: Long, alsoServer: Boolean = true): Int {
        val run = getById(id, withTrack = false) ?: return 0
        val n = if (run.onServer && alsoServer) {
            db.update("runs", ContentValues().apply {
                put("deleted", 1); put("sync_status", SyncStatus.PENDING.name); put("updated_at", System.currentTimeMillis())
            }, "id = ?", arrayOf(id.toString()))
        } else {
            db.delete("runs", "id = ?", arrayOf(id.toString()))
        }
        notifyChanged()
        return n
    }

    fun hardDelete(id: Long) {
        db.delete("runs", "id = ?", arrayOf(id.toString()))
        notifyChanged()
    }

    private fun writePoints(d: SQLiteDatabase, runId: Long, fromSeq: Int, points: List<TrackPoint>) {
        val st = d.compileStatement("INSERT OR REPLACE INTO run_points (run_id, seq, lat, lng, time, alt, acc) VALUES (?,?,?,?,?,?,?)")
        points.forEachIndexed { i, p ->
            st.clearBindings()
            st.bindLong(1, runId); st.bindLong(2, (fromSeq + i).toLong())
            st.bindDouble(3, p.lat); st.bindDouble(4, p.lng); st.bindLong(5, p.time)
            st.bindDouble(6, p.altitude); st.bindDouble(7, p.accuracy.toDouble())
            st.executeInsert()
        }
    }

    /** splits + เส้นทางย่อ คำนวณครั้งเดียวตอนบันทึก (หน้ารายการจะได้ไม่ต้องโหลดจุดทั้งหมด) */
    private fun writeTrackDerived(d: SQLiteDatabase, runId: Long, track: List<TrackPoint>) {
        d.delete("splits", "run_id = ?", arrayOf(runId.toString()))
        TrackMath.splits(track).forEach { s ->
            d.insert("splits", null, ContentValues().apply {
                put("run_id", runId); put("km", s.km); put("pace_s", s.paceSecPerKm); put("elev_delta_m", s.elevationDeltaM)
            })
        }
        d.update("runs", ContentValues().apply { put("thumb", Codec.encodeTrack(Codec.downsample(track))) },
            "id = ?", arrayOf(runId.toString()))
    }

    /** แทนที่จุด GPS ทั้งหมด (ใช้ตอนดึงการวิ่งจาก server ลงเครื่อง) */
    fun replaceTrack(runId: Long, track: List<TrackPoint>) {
        db.transaction {
            delete("run_points", "run_id = ?", arrayOf(runId.toString()))
            writePoints(this, runId, 0, track)
            writeTrackDerived(this, runId, track)
        }
        notifyChanged()
    }

    fun hasTrack(runId: Long): Boolean =
        db.rawQuery("SELECT 1 FROM run_points WHERE run_id = ? LIMIT 1", arrayOf(runId.toString())).use { it.moveToFirst() }

    // ---------------------------------------------------------------- การวิ่งที่ยังไม่จบ (draft)

    /** เริ่มบันทึกการวิ่ง: สร้างแถว end_time = NULL ไว้ก่อน เพื่อกู้คืนได้ถ้าแอปถูกปิดกลางคัน */
    fun startDraft(startTime: Long): Run {
        val run = Run(title = "", startTime = startTime, ownerUserId = currentUser())
        val id = db.insertOrThrow("runs", null, values(run))
        return run.copy(id = id)
    }

    /** เขียนจุด GPS ที่เพิ่มขึ้น + ตัวเลขล่าสุดของ draft (เรียกทุกไม่กี่วินาที) */
    fun saveDraft(run: Run, newPoints: List<TrackPoint>, fromSeq: Int) {
        db.transaction {
            writePoints(this, run.id, fromSeq, newPoints)
            update("runs", ContentValues().apply {
                put("distance_m", run.distanceM); put("moving_time_s", run.movingTimeS); put("calories", run.calories)
                put("elevation_gain_m", run.elevationGainM); put("cadence_spm", run.cadenceSpm); put("temp_c", run.tempC)
                put("photo_path", run.photoUri); put("photo_sync", run.photoSync.name)
                put("pauses", Codec.encodePauses(run.pauses)); put("updated_at", System.currentTimeMillis())
            }, "id = ?", arrayOf(run.id.toString()))
        }
    }

    /** การวิ่งที่ค้างอยู่ (ถ้ามี) พร้อมจุด GPS ทั้งหมด */
    fun activeDraft(): Run? =
        queryRuns("end_time IS NULL AND deleted = 0", emptyArray(), "start_time DESC", withTrack = true).firstOrNull()

    /** จบ draft ให้เป็นการวิ่งจริง (ทั้งจากการกดจบ และจาก "บันทึกเท่าที่มี" ตอนกู้คืน) */
    fun finishDraft(run: Run, endTime: Long, track: List<TrackPoint>) {
        db.transaction {
            val r = run.copy(endTime = endTime, syncStatus = SyncStatus.PENDING,
                updatedAt = System.currentTimeMillis(), ownerUserId = run.ownerUserId ?: currentUser())
            update("runs", values(r), "id = ?", arrayOf(run.id.toString()))
            delete("run_points", "run_id = ?", arrayOf(run.id.toString()))
            writePoints(this, run.id, 0, track)
            writeTrackDerived(this, run.id, track)
        }
        notifyChanged()
    }

    fun discardDraft(id: Long) {
        db.delete("runs", "id = ? AND end_time IS NULL", arrayOf(id.toString()))
    }

    // ---------------------------------------------------------------- ซิงก์ (ใช้โดย SyncEngine)

    /** การวิ่งที่ต้องส่งขึ้น server ของผู้ใช้นี้ (จบแล้ว ยังไม่ซิงก์) */
    fun runsToPush(userId: String): List<Run> =
        queryRuns("end_time IS NOT NULL AND deleted = 0 AND owner = ? AND sync_status != ?",
            arrayOf(userId, SyncStatus.SYNCED.name), "start_time", withTrack = false)

    fun tombstones(userId: String): List<Run> =
        queryRuns("deleted = 1 AND owner = ?", arrayOf(userId), null, withTrack = false)

    fun photosToSync(userId: String): List<Run> =
        queryRuns("end_time IS NOT NULL AND deleted = 0 AND owner = ? AND on_server = 1 AND photo_sync IN (?, ?)",
            arrayOf(userId, PhotoSync.PENDING.name, PhotoSync.DELETE.name), null, withTrack = false)

    /** Guest → ผูกการวิ่งทั้งหมดที่ยังไม่มีเจ้าของให้บัญชีที่เพิ่งล็อกอิน */
    fun claimGuestRuns(userId: String): Int {
        val n = db.update("runs", ContentValues().apply { put("owner", userId) }, "owner IS NULL", null)
        if (n > 0) notifyChanged()
        return n
    }

    /**
     * อัปเดตเฉพาะถ้าผู้ใช้ไม่ได้แก้ระหว่างที่กำลังส่ง (updated_at ยังเท่าเดิม)
     * คืน true ถ้าเขียนได้
     */
    fun updateIfUnchanged(run: Run, expectedUpdatedAt: Long): Boolean {
        val n = db.update("runs", values(run), "id = ? AND updated_at = ?",
            arrayOf(run.id.toString(), expectedUpdatedAt.toString()))
        if (n > 0) notifyChanged()
        return n > 0
    }

    fun setSyncState(id: Long, status: SyncStatus, error: String?) {
        db.update("runs", ContentValues().apply { put("sync_status", status.name); put("sync_error", error) },
            "id = ?", arrayOf(id.toString()))
        notifyChanged()
    }

    fun markOnServer(id: Long) {
        db.update("runs", ContentValues().apply { put("on_server", 1) }, "id = ?", arrayOf(id.toString()))
    }

    fun setPhotoSync(id: Long, state: PhotoSync) {
        db.update("runs", ContentValues().apply { put("photo_sync", state.name) }, "id = ?", arrayOf(id.toString()))
        notifyChanged()
    }

    fun setPhotoPath(id: Long, path: String) {
        db.update("runs", ContentValues().apply { put("photo_path", path) }, "id = ?", arrayOf(id.toString()))
        notifyChanged()
    }

    /** บันทึกการวิ่งที่ดึงมาจาก server (ไม่ทำเป็นการแก้ของผู้ใช้) คืน id ในเครื่อง */
    fun upsertFromServer(run: Run): Long {
        val existing = getByUuid(run.uuid)
        val id: Long
        if (existing == null) {
            id = db.insertOrThrow("runs", null, values(run))
        } else {
            id = existing.id
            db.update("runs", values(run.copy(id = id, photoUri = run.photoUri ?: existing.photoUri)), "id = ?", arrayOf(id.toString()))
        }
        notifyChanged()
        return id
    }

    /** ออกจากระบบ: ล้างข้อมูลทั้งหมดในเครื่อง */
    fun clearAll() {
        db.transaction {
            delete("run_points", null, null)
            delete("splits", null, null)
            delete("runs", null, null)
        }
        notifyChanged()
    }

    // ---------------------------------------------------------------- ค้นหา / สถิติ

    /** ค้นหา + กรอง + เรียง */
    fun query(text: String, filter: Filter, sort: Sort): List<Run> {
        val now = System.currentTimeMillis()
        val weekStart = startOfWeek(now)
        val monthStart = startOfMonth(now)
        val q = text.trim()
        val filtered = getAll().filter { run ->
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
        return getAll().filter { it.startTime >= start && it.startTime <= now }
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
        val all = getAll()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val result = mutableListOf<Pair<Int, Float>>()
        for (i in months - 1 downTo 0) {
            val c = cal.clone() as Calendar
            c.add(Calendar.MONTH, -i)
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH)
            val km = all.filter {
                val rc = Calendar.getInstance().apply { timeInMillis = it.startTime }
                rc.get(Calendar.YEAR) == y && rc.get(Calendar.MONTH) == m
            }.sumOf { it.distanceM } / 1000.0
            result += m to km.toFloat()
        }
        return result
    }

    fun splits(run: Run): List<Split> = run.splits.ifEmpty { TrackMath.splits(run.track) }

    fun route(run: Run): List<RoutePoint> = RouteGenerator.route(run)

    fun pendingCount(): Int = getAll().count { it.syncStatus != SyncStatus.SYNCED }

    fun startOfWeek(now: Long): Long = Periods.startOfWeek(now)

    fun startOfMonth(now: Long): Long = Periods.startOfMonth(now)

    const val DAY_MS = Periods.DAY_MS
    const val WEEK_MS = Periods.WEEK_MS

    private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }
}

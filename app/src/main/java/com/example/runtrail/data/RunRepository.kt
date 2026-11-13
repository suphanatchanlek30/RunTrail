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
 * All reads and writes of runs.
 *
 * "Visible" runs are finished, not deleted, and owned by the current user or by nobody (guest).
 */
object RunRepository {

    enum class Filter { ALL, THIS_WEEK, THIS_MONTH, OVER_5K }
    enum class Sort { NEWEST, OLDEST, LONGEST, FASTEST }

    private lateinit var helper: RunDb
    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Signed-in user id, or null for a guest. */
    @Volatile var currentUser: () -> String? = { null }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context, database: RunDb = RunDb(context.applicationContext)) {
        if (::helper.isInitialized) helper.close()
        helper = database
    }

    /** Screens register here to refresh when background sync changes data. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    /** Notifies listeners on the main thread, coalescing bursts of writes into one callback. */
    fun notifyChanged() {
        if (notifyPending.compareAndSet(false, true)) main.postDelayed(dispatch, NOTIFY_DELAY_MS)
    }

    private val notifyPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val dispatch = Runnable {
        notifyPending.set(false)
        dispatchCount++
        listeners.forEach { it() }
    }

    /** Number of listener dispatches; used by tests to check coalescing. */
    @Volatile var dispatchCount = 0
        private set
    private const val NOTIFY_DELAY_MS = 120L

    // ---------------------------------------------------------------- Reads

    private fun visibleWhere(): Pair<String, Array<String>> {
        val user = currentUser()
        return if (user == null) {
            "end_time IS NOT NULL AND deleted = 0 AND owner IS NULL" to emptyArray()
        } else {
            "end_time IS NOT NULL AND deleted = 0 AND (owner IS NULL OR owner = ?)" to arrayOf(user)
        }
    }

    /** Visible runs, newest first, with thumbnail and splits but without the full track. */
    fun getAll(): List<Run> {
        val (where, args) = visibleWhere()
        return queryRuns(where, args, "start_time DESC", withTrack = false)
    }

    fun getById(id: Long, withTrack: Boolean = true): Run? =
        queryRuns("id = ?", arrayOf(id.toString()), null, withTrack).firstOrNull()

    fun getByUuid(uuid: String, withTrack: Boolean = false): Run? =
        queryRuns("uuid = ?", arrayOf(uuid), null, withTrack).firstOrNull()

    /** The newest [limit] visible runs. */
    fun recent(limit: Int): List<Run> {
        val (where, args) = visibleWhere()
        return queryRuns(where, args, "start_time DESC", withTrack = false, limit = limit.toString())
    }

    /** Visible runs of at least [minMeters] that started before [before]; used for the new-record badge. */
    fun longRunsBefore(minMeters: Double, before: Long): List<Run> {
        val (where, args) = visibleWhere()
        return queryRuns("$where AND distance_m >= ? AND start_time < ?", args + minMeters.toString() + before.toString(),
            null, withTrack = false)
    }

    /** Visible runs that started in [start, until). */
    fun since(start: Long, until: Long = Long.MAX_VALUE): List<Run> {
        val (where, args) = visibleWhere()
        return queryRuns("$where AND start_time >= ? AND start_time <= ?", args + start.toString() + until.toString(),
            "start_time DESC", withTrack = false)
    }

    private fun queryRuns(where: String, args: Array<String>, order: String?, withTrack: Boolean, limit: String? = null): List<Run> {
        val out = mutableListOf<Run>()
        db.query("runs", null, where, args, null, null, order, limit).use { c ->
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
            feel = c.getInt(c.getColumnIndexOrThrow("feel")),
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

    // ---------------------------------------------------------------- Writes

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
        put("feel", r.feel)
    }

    /** Inserts a finished run with its track and derives splits and the thumbnail. Returns the new id. */
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

    /** Updates run fields (not the track). With [touch], counts as a user edit: bumps `updatedAt` and marks it pending. */
    fun update(run: Run, touch: Boolean = true): Int {
        val r = if (touch) run.copy(updatedAt = maxOf(System.currentTimeMillis(), run.updatedAt + 1),
            syncStatus = SyncStatus.PENDING, syncError = null) else run
        val n = db.update("runs", values(r), "id = ?", arrayOf(r.id.toString()))
        if (n > 0) notifyChanged()
        return n
    }

    /** Deletes a run. Runs already on the server become tombstones when [alsoServer]; others are removed at once. */
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

    /** Recomputes splits and the thumbnail so list screens never load full tracks. */
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

    /** Replaces the whole track, e.g. after downloading a run from the server. */
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

    // ---------------------------------------------------------------- Drafts (runs in progress)

    /** Creates the draft row up front so a run can be recovered if the app is killed. */
    fun startDraft(startTime: Long): Run {
        val run = Run(title = "", startTime = startTime, ownerUserId = currentUser())
        val id = db.insertOrThrow("runs", null, values(run))
        return run.copy(id = id)
    }

    /** Appends new points and the latest totals to a draft. Called every few seconds while recording. */
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

    /** The unfinished run, if any, with its full track. */
    fun activeDraft(): Run? =
        queryRuns("end_time IS NULL AND deleted = 0", emptyArray(), "start_time DESC", withTrack = true).firstOrNull()

    /** Turns a draft into a finished run, from either Stop or "save what was recorded" on recovery. */
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

    // ---------------------------------------------------------------- Sync (used by SyncEngine)

    /** Finished, non-deleted runs of [userId] that still need uploading. */
    fun runsToPush(userId: String): List<Run> =
        queryRuns("end_time IS NOT NULL AND deleted = 0 AND owner = ? AND sync_status != ?",
            arrayOf(userId, SyncStatus.SYNCED.name), "start_time", withTrack = false)

    fun tombstones(userId: String): List<Run> =
        queryRuns("deleted = 1 AND owner = ?", arrayOf(userId), null, withTrack = false)

    fun photosToSync(userId: String): List<Run> =
        queryRuns("end_time IS NOT NULL AND deleted = 0 AND owner = ? AND on_server = 1 AND photo_sync IN (?, ?)",
            arrayOf(userId, PhotoSync.PENDING.name, PhotoSync.DELETE.name), null, withTrack = false)

    /** Assigns all guest runs to [userId] after sign-in. */
    fun claimGuestRuns(userId: String): Int {
        val n = db.update("runs", ContentValues().apply { put("owner", userId) }, "owner IS NULL", null)
        if (n > 0) notifyChanged()
        return n
    }

    /** Writes [run] only if the row was not edited meanwhile (`updated_at` still equals [expectedUpdatedAt]). */
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

    /** Inserts or updates a run downloaded from the server without marking it as a user edit. Returns the local id. */
    fun upsertFromServer(run: Run, clearLocalPhoto: Boolean = false): Long {
        val existing = getByUuid(run.uuid)
        val id: Long
        if (existing == null) {
            id = db.insertOrThrow("runs", null, values(run))
        } else {
            id = existing.id
            val photo = if (clearLocalPhoto) null else run.photoUri ?: existing.photoUri
            db.update("runs", values(run.copy(id = id, photoUri = photo)), "id = ?", arrayOf(id.toString()))
        }
        notifyChanged()
        return id
    }

    // ---------------------------------------------------------------- Backup

    /** Visible runs with full tracks, for export. */
    fun allWithTracks(): List<Run> = getAll().map { it.copy(track = loadTrack(it.id)) }

    /** Inserts backup runs whose uuid is not on the device yet, owned by the current user. Returns the number added. */
    fun importRuns(runs: List<Run>): Int {
        var added = 0
        db.transaction {
            for (run in runs) {
                val exists = rawQuery("SELECT 1 FROM runs WHERE uuid = ? LIMIT 1", arrayOf(run.uuid)).use { it.moveToFirst() }
                if (exists) continue
                val r = run.copy(id = 0, ownerUserId = currentUser())
                val id = insertOrThrow("runs", null, values(r))
                writeTrackDerived(this, id, r.track)
                writePoints(this, id, 0, r.track)
                added++
            }
        }
        if (added > 0) notifyChanged()
        return added
    }

    /** Deletes every run on the device (sign-out). */
    fun clearAll() {
        db.transaction {
            delete("run_points", null, null)
            delete("splits", null, null)
            delete("runs", null, null)
        }
        notifyChanged()
    }

    // ---------------------------------------------------------------- Search and stats

    fun query(text: String, filter: Filter, sort: Sort): List<Run> = filterAndSort(getAll(), text, filter, sort)

    /** Filters and sorts an already loaded list, so typing in search does not hit the database. */
    fun filterAndSort(all: List<Run>, text: String, filter: Filter, sort: Sort, now: Long = System.currentTimeMillis()): List<Run> {
        val weekStart = startOfWeek(now)
        val monthStart = startOfMonth(now)
        val q = text.trim()
        val filtered = all.filter { run ->
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

    /** Runs in the last 7 days, including today. */
    fun thisWeek(now: Long = System.currentTimeMillis(), all: List<Run> = getAll()): List<Run> {
        val start = startOfWeek(now)
        return all.filter { it.startTime >= start && it.startTime <= now }
    }

    /** Distance per day for the last 7 days (index 6 = today), in km or miles. */
    fun weekDailyKm(now: Long = System.currentTimeMillis(), all: List<Run> = getAll(), miles: Boolean = false): FloatArray {
        val start = startOfWeek(now)
        val out = FloatArray(7)
        thisWeek(now, all).forEach { run ->
            val day = ((run.startTime - start) / DAY_MS).toInt().coerceIn(0, 6)
            out[day] += com.example.runtrail.domain.Units.inUnit(run.distanceM, miles).toFloat()
        }
        return out
    }

    /** Short weekday labels for the last 7 days, oldest first. */
    fun weekDayLetters(now: Long = System.currentTimeMillis()): List<String> {
        val letters = com.example.runtrail.domain.Fmt.DAY_LETTERS
        val start = startOfWeek(now)
        return List(7) { i ->
            val c = Calendar.getInstance().apply { timeInMillis = start + i * DAY_MS }
            letters[c.get(Calendar.DAY_OF_WEEK) - 1]
        }
    }

    /** Total distance per month for the last [months] months, oldest first. */
    fun monthlyKm(months: Int = 6, now: Long = System.currentTimeMillis(), all: List<Run> = getAll(), miles: Boolean = false): List<Pair<Int, Float>> {
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
            }.sumOf { it.distanceM }
            result += m to com.example.runtrail.domain.Units.inUnit(km, miles).toFloat()
        }
        return result
    }

    fun splits(run: Run): List<Split> = run.splits.ifEmpty { TrackMath.splits(run.track) }

    fun route(run: Run): List<RoutePoint> = RouteGenerator.route(run)

    /** Number of runs waiting to sync, counted in SQL. */
    fun pendingCount(): Int {
        val (where, args) = visibleWhere()
        return db.rawQuery("SELECT count(*) FROM runs WHERE $where AND sync_status != ?", args + SyncStatus.SYNCED.name)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

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

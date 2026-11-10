package com.example.runtrail.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.TrackPoint
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.LiveRunState
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.domain.TrackMath
import com.example.runtrail.sensor.CadenceTracker
import com.example.runtrail.sensor.LocationTracker
import com.example.runtrail.sync.SyncManager
import com.example.runtrail.ui.live.LiveRunActivity
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Foreground service (type = location) ที่ "เป็นเจ้าของ" การวิ่งสด
 *
 * - GPS / Step counter / เวลา ทำงานต่อแม้ปิดจอ สลับแอป หรือปิดหน้าวิ่ง
 * - เขียนจุด GPS ที่เพิ่มขึ้นลง SQLite ทุก 5 วินาที → ถ้าแอปถูกระบบปิด หน้าหลักจะถามกู้คืน
 * - LiveRunActivity เป็นแค่หน้าจอแสดงผล: อ่านค่าจาก [current] และสั่ง pause / resume / finish
 * - START_NOT_STICKY: ถ้าระบบฆ่า process จะไม่เริ่มใหม่เอง (ใช้ draft ใน DB + dialog กู้คืนแทน)
 */
class RunTrackingService : Service() {

    lateinit var state: LiveRunState
        private set
    private lateinit var draft: Run
    private lateinit var prefs: PrefsHelper
    private lateinit var tracker: LocationTracker
    private lateinit var cadence: CadenceTracker
    private val handler = Handler(Looper.getMainLooper())
    private var savedPoints = 0
    private var ticks = 0
    private var cadenceSum = 0L
    private var cadenceCount = 0
    private var started = false

    var photoPath: String? = null
        set(value) { field = value; persist() }
    var tempC: Float? = null
        private set
    val runId: Long get() = draft.id
    val hasPermission: Boolean get() = tracker.hasPermission
    val gpsEnabled: Boolean get() = tracker.gpsEnabled
    val lastKnown get() = tracker.lastKnown

    /** หน้าจอที่เปิดอยู่ (ถ้ามี) — ถูกเรียกบน main thread ทุกครั้งที่ค่ามีการเปลี่ยน */
    var listener: (() -> Unit)? = null

    private val tempListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { if (e.values[0] in -40f..80f) tempC = e.values[0] }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE, ACTION_RESUME -> when {
                !started -> stopSelf()   // กดจากการแจ้งเตือนเก่าหลังการวิ่งจบไปแล้ว
                intent.action == ACTION_PAUSE -> pause()
                else -> resume()
            }
            else -> if (!started && !begin(intent?.getBooleanExtra(EXTRA_RESUME, false) == true)) stopSelf()
        }
        return START_NOT_STICKY
    }

    /** เริ่มการวิ่งใหม่ หรือกู้คืนจาก draft · คืน false ถ้าเริ่มไม่ได้ */
    private fun begin(resume: Boolean): Boolean {
        prefs = PrefsHelper(this)
        tracker = LocationTracker(this, ::onLocation) { listener?.invoke() }
        if (!tracker.hasPermission) return false   // Android 14+ ห้ามเริ่ม location service ถ้ายังไม่อนุญาต

        val existing = RunRepository.activeDraft()
        if (resume && existing != null) {
            draft = existing
            state = LiveRunState(existing.startTime) { prefs.autoPause }
            state.restore(existing.track, existing.distanceM, existing.movingTimeS, existing.pauses, System.currentTimeMillis())
            savedPoints = existing.track.size
            photoPath = existing.photoUri
        } else {
            existing?.let { RunRepository.discardDraft(it.id) }
            val now = System.currentTimeMillis()
            draft = RunRepository.startDraft(now)
            state = LiveRunState(now) { prefs.autoPause }
        }

        createChannels(this)
        try {
            ServiceCompat.startForeground(this, NOTIFY_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.w(TAG, "cannot start foreground location service", e)
            return false
        }
        started = true
        current = this
        tracker.start()
        cadence = CadenceTracker(this).also { it.start() }
        (getSystemService(SENSOR_SERVICE) as SensorManager).let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let { sm.registerListener(tempListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        handler.postDelayed(ticker, 1000)
        return true
    }

    private fun onLocation(p: TrackPoint, speed: Float?) {
        val km = state.onLocation(p, speed, System.currentTimeMillis())
        if (km > 0) kmAlert(km)
        listener?.invoke()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        state.tick(SystemClock.elapsedRealtime(), now)
        val c = cadence.cadence(now)
        if (c > 0 && !state.paused) { cadenceSum += c; cadenceCount++ }
        ticks++
        if (ticks % 5 == 0) persist()
        if (ticks % 3 == 0) updateNotification()
        listener?.invoke()
    }

    fun pause() {
        state.pause(System.currentTimeMillis(), auto = false)
        persist(); updateNotification(); listener?.invoke()
    }

    fun resume() {
        state.resume(System.currentTimeMillis())
        persist(); updateNotification(); listener?.invoke()
    }

    fun restartGps() {
        if (tracker.hasPermission && !tracker.running) tracker.start()
    }

    fun calories(): Double = RunCalculator.calories(state.distanceM, state.movingS, prefs.weightKg)

    fun avgCadence(): Int = if (cadenceCount > 0) (cadenceSum / cadenceCount).toInt() else 0

    fun liveCadence(): Int = cadence.cadence()

    private fun snapshot(): Run = draft.copy(
        distanceM = state.distanceM,
        movingTimeS = state.movingS,
        calories = calories(),
        elevationGainM = TrackMath.elevationGain(state.track),
        cadenceSpm = avgCadence(),
        tempC = tempC?.roundToInt(),
        photoUri = photoPath,
        photoSync = if (photoPath != null) PhotoSync.PENDING else PhotoSync.NONE,
        pauses = state.allPauses(System.currentTimeMillis()),
    )

    /** เขียนจุดที่เพิ่มขึ้นและตัวเลขล่าสุดลง SQLite */
    private fun persist() {
        if (!started) return
        val pts = state.track
        val newPoints = if (pts.size > savedPoints) pts.subList(savedPoints, pts.size).toList() else emptyList()
        RunRepository.saveDraft(snapshot(), newPoints, savedPoints)
        savedPoints = pts.size
    }

    /** จบการวิ่ง → บันทึกเป็นการวิ่งจริง แล้วสั่งซิงก์ (ถ้าไม่มีเน็ต WorkManager จะรอเอง) · คืน id */
    fun finish(): Long {
        val end = System.currentTimeMillis()
        val run = snapshot().copy(title = defaultTitle(draft.startTime))
        RunRepository.finishDraft(run, end, state.track.toList())
        SyncManager.requestSync(this)
        shutdown()
        return run.id
    }

    /** ทิ้งการวิ่งนี้ */
    fun discard() {
        RunRepository.discardDraft(draft.id)
        shutdown()
    }

    private fun shutdown() {
        started = false
        current = null
        handler.removeCallbacks(ticker)
        tracker.stop()
        cadence.stop()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(tempListener)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) {
            persist()   // ถูกปิดโดยไม่ได้กดจบ → เก็บ draft ไว้ให้กู้คืน
            started = false
            current = null
            handler.removeCallbacks(ticker)
            tracker.stop()
            cadence.stop()
            (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(tempListener)
        }
        super.onDestroy()
    }

    private fun defaultTitle(start: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.HOUR_OF_DAY)
        return getString(
            when (hour) {
                in 4..10 -> R.string.title_morning_run
                in 11..15 -> R.string.title_afternoon_run
                in 16..19 -> R.string.title_evening_run
                else -> R.string.title_night_run
            },
        )
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, LiveRunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = started && state.paused
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, RunTrackingService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val miles = if (::prefs.isInitialized) prefs.useMiles else false
        val text = if (!started) getString(R.string.notify_run_starting) else getString(
            R.string.notify_run_text, Fmt.distance(state.distanceM, miles), Fmt.unit(miles), Fmt.duration(state.movingS),
        )
        return NotificationCompat.Builder(this, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_activity)
            .setContentTitle(getString(if (paused) R.string.notify_run_paused else R.string.notify_run_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                getString(if (paused) R.string.live_resume else R.string.live_pause), toggle,
            )
            .build()
    }

    private fun updateNotification() {
        if (!started) return
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFY_ID, buildNotification())
            } catch (e: SecurityException) {
                Log.w(TAG, "notification permission revoked", e)
            }
        }
    }

    private fun kmAlert(km: Int) {
        if (!prefs.kmAlert || !NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val pace = state.splitPace(km)?.let { Fmt.pace(it) } ?: "--:--"
        val n = NotificationCompat.Builder(this, CHANNEL_KM)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(getString(R.string.notify_km_title, km))
            .setContentText(getString(R.string.notify_km_text, pace, Fmt.duration(state.movingS)))
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(KM_NOTIFY_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }

    companion object {
        private const val TAG = "RunTracking"
        const val ACTION_START = "com.example.runtrail.action.START"
        const val ACTION_PAUSE = "com.example.runtrail.action.PAUSE"
        const val ACTION_RESUME = "com.example.runtrail.action.RESUME"
        const val EXTRA_RESUME = "resume"
        private const val NOTIFY_ID = 42
        private const val KM_NOTIFY_ID = 43
        const val CHANNEL_RUN = "run_tracking"
        const val CHANNEL_KM = "km_alert"
        const val CHANNEL_UPDATES = "updates"

        /** การวิ่งที่กำลังบันทึกอยู่ (null = ไม่มี) */
        @Volatile var current: RunTrackingService? = null
            private set

        val isRunning: Boolean get() = current != null

        /** เริ่มบันทึก (resume = กู้คืน draft ล่าสุด) — เรียกหลังได้สิทธิ์ตำแหน่งแล้วเท่านั้น */
        fun start(context: Context, resume: Boolean) {
            val i = Intent(context, RunTrackingService::class.java).setAction(ACTION_START).putExtra(EXTRA_RESUME, resume)
            ContextCompat.startForegroundService(context, i)
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUN, context.getString(R.string.channel_run), NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_KM, context.getString(R.string.channel_km), NotificationManager.IMPORTANCE_DEFAULT),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

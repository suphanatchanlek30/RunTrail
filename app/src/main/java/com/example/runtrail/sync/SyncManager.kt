package com.example.runtrail.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.runtrail.App
import java.util.concurrent.TimeUnit

/**
 * ตั้งเวลาซิงก์ด้วย WorkManager: ทำงานเมื่อมีเน็ตเท่านั้น ถ้าไม่มีเน็ตจะรอจนต่อได้แล้วทำเอง
 * (แบบ Strava: วิ่งตอนออฟไลน์ แล้วอัปโหลดเองเมื่อกลับมามีเน็ต)
 */
object SyncManager {

    private const val ONE_TIME = "sync_now"
    private const val PERIODIC = "sync_periodic"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** ขอซิงก์ (เช่น หลังจบการวิ่ง แก้ไข หรือลบ) — ถ้ากำลังซิงก์อยู่จะต่อคิวอีกรอบ */
    fun requestSync(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** ซิงก์ทุก 6 ชั่วโมงเพื่อดึงข้อมูลจากเครื่องอื่น */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** ออกจากระบบ: หยุดงานซิงก์ทั้งหมดก่อนล้างข้อมูล */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = when (App.services.sync.sync()) {
        is SyncEngine.Outcome.Done, SyncEngine.Outcome.NotLoggedIn, SyncEngine.Outcome.SessionExpired -> Result.success()
        is SyncEngine.Outcome.Retry -> if (runAttemptCount < 8) Result.retry() else Result.failure()
    }
}

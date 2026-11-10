package com.example.runtrail

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.RunRepository
import com.example.runtrail.net.ApiClient
import com.example.runtrail.net.RunTrailApi
import com.example.runtrail.net.TokenStore
import com.example.runtrail.service.RunTrackingService
import com.example.runtrail.sync.SyncEngine
import com.example.runtrail.sync.SyncManager
import java.util.concurrent.Executors

/**
 * จุดเริ่มต้นของแอป: เปิดฐานข้อมูล สร้างตัวเชื่อม backend และตั้งเวลาซิงก์
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        services = Services(this)
        // ใช้ธีมที่ผู้ใช้เลือกไว้ (ระบบ / กลางวัน / กลางคืน) ก่อนเปิดหน้าจอแรก
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(services.prefs.themeMode)
        // ภาษาของข้อความที่สร้างในโค้ด (Fmt, PaceColor, ข้อความ error) ให้ตรงกับภาษาที่เลือก
        com.example.runtrail.domain.Lang.english = { englishUi }
        refreshLanguage()
        registerActivityLifecycleCallbacks(object : SimpleActivityCallbacks() {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = refreshLanguage()
        })
        RunRepository.init(this)
        RunRepository.currentUser = { services.prefs.accountUserId }
        RunTrackingService.createChannels(this)
        services.onSessionExpired = {
            android.widget.Toast.makeText(this, R.string.session_expired, android.widget.Toast.LENGTH_LONG).show()
        }
        if (!isInstrumentationTest()) {
            SyncManager.schedulePeriodic(this)
            if (services.sessions.get() != null) SyncManager.requestSync(this)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguage()
    }

    @Volatile private var englishUi = false

    /** อ่านภาษาที่ผู้ใช้เลือก (ถ้าไม่ได้เลือก = ภาษาของเครื่อง) แล้วเก็บไว้ ไม่ต้องถามระบบทุกครั้งที่จัดรูปแบบข้อความ */
    fun refreshLanguage() {
        val app = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val locale = if (!app.isEmpty) app[0]
        else androidx.core.os.ConfigurationCompat.getLocales(android.content.res.Resources.getSystem().configuration)[0]
        englishUi = locale?.language == "en"
    }

    /** ระหว่าง instrumented test ไม่ตั้งงานซิงก์เอง (test เรียก SyncEngine ตรง ๆ) */
    private fun isInstrumentationTest(): Boolean = try {
        Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    companion object {
        lateinit var services: Services
            private set
    }
}

/** ของที่ใช้ร่วมกันทั้งแอป (สร้างครั้งเดียว) */
class Services(val context: Context) {
    val prefs = PrefsHelper(context)
    val sessions = TokenStore(context)
    private val main = Handler(Looper.getMainLooper())

    /** ถูกเรียกเมื่อ refresh token ใช้ไม่ได้แล้ว → ต้องล็อกอินใหม่ (ข้อมูลในเครื่องยังอยู่) */
    @Volatile var onSessionExpired: (() -> Unit)? = null

    val api = RunTrailApi(ApiClient({ prefs.apiUrl }, sessions, onSessionExpired = {
        main.post { onSessionExpired?.invoke() }
    }))

    val sync = SyncEngine(context, api, prefs)

    /** thread สำหรับงานเครือข่ายที่สั่งจากหน้าจอ */
    val io = Executors.newFixedThreadPool(3)

    val deviceName: String
        get() = (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL).take(100)
}

/** ActivityLifecycleCallbacks ที่ไม่ต้อง override ทุกเมธอด */
open class SimpleActivityCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) {}
    override fun onActivityResumed(activity: android.app.Activity) {}
    override fun onActivityPaused(activity: android.app.Activity) {}
    override fun onActivityStopped(activity: android.app.Activity) {}
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
}

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

/** Application entry point: wires shared services, opens the database and schedules background work. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        services = Services(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(services.prefs.themeMode)
        // Formatting helpers in domain/ read the UI language through this hook.
        com.example.runtrail.domain.Lang.english = { englishUi }
        refreshLanguage()
        registerActivityLifecycleCallbacks(object : SimpleActivityCallbacks() {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = refreshLanguage()
        })
        RunRepository.init(this)
        RunRepository.currentUser = { services.prefs.accountUserId }
        RunTrackingService.createChannels(this)
        // Re-arm the reminder; alarms are cleared by force-stop and app updates.
        com.example.runtrail.reminder.ReminderNotifier.createChannel(this)
        com.example.runtrail.reminder.ReminderScheduler.schedule(this)
        services.onSessionExpired = {
            android.widget.Toast.makeText(this, R.string.session_expired, android.widget.Toast.LENGTH_LONG).show()
        }
        if (!isInstrumentationTest()) {
            services.wakeServer()
            SyncManager.schedulePeriodic(this)
            if (services.sessions.get() != null) SyncManager.requestSync(this)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguage()
    }

    @Volatile private var englishUi = false

    /** Caches whether the UI language is English (app locale, else system locale). */
    fun refreshLanguage() {
        val app = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val locale = if (!app.isEmpty) app[0]
        else androidx.core.os.ConfigurationCompat.getLocales(android.content.res.Resources.getSystem().configuration)[0]
        englishUi = locale?.language == "en"
    }

    /** Instrumented tests drive sync themselves, so background scheduling is skipped. */
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

/** App-wide singletons, created once in [App.onCreate]. */
class Services(val context: Context) {
    val prefs = PrefsHelper(context)
    val sessions = TokenStore(context)
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread when the refresh token is rejected and the user must sign in again. */
    @Volatile var onSessionExpired: (() -> Unit)? = null

    val api = RunTrailApi(ApiClient({ prefs.apiUrl }, sessions, onSessionExpired = {
        main.post { onSessionExpired?.invoke() }
    }))

    val sync = SyncEngine(context, api, prefs)

    init {
        // Volley requests share the server address and session with the OkHttp client.
        com.example.runtrail.net.ApiConfig.baseUrl = { prefs.apiUrl }
        com.example.runtrail.net.VolleyApi.client = api.client
    }

    /** Background pool for network calls started from screens. */
    val io = Executors.newFixedThreadPool(3)

    /** Fire-and-forget health check so a sleeping free-tier server starts waking before the user needs it. */
    fun wakeServer() {
        io.execute { api.health() }
    }

    val deviceName: String
        get() = (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL).take(100)
}

/** [Application.ActivityLifecycleCallbacks] with empty defaults. */
open class SimpleActivityCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) {}
    override fun onActivityResumed(activity: android.app.Activity) {}
    override fun onActivityPaused(activity: android.app.Activity) {}
    override fun onActivityStopped(activity: android.app.Activity) {}
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
}

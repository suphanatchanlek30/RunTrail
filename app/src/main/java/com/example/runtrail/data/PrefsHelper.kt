package com.example.runtrail.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.runtrail.BuildConfig

/**
 * ค่าตั้งค่าและโปรไฟล์ทั้งหมด (MA09 SharedPreferences)
 */
class PrefsHelper(context: Context, name: String = PREFS_NAME) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDED, value) }

    var firstNameValue: String
        get() = prefs.getString(KEY_FIRST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_FIRST, value.trim()); putBoolean(KEY_PROFILE_DIRTY, true) }

    var lastName: String
        get() = prefs.getString(KEY_LAST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST, value.trim()); putBoolean(KEY_PROFILE_DIRTY, true) }

    /** ชื่อ-นามสกุลเต็ม */
    val name: String get() = "$firstNameValue $lastName".trim()

    var weightKg: Int
        get() = prefs.getInt(KEY_WEIGHT, 62)
        set(value) = prefs.edit { putInt(KEY_WEIGHT, value); putBoolean(KEY_PROFILE_DIRTY, true) }

    var heightCm: Int
        get() = prefs.getInt(KEY_HEIGHT, 172)
        set(value) = prefs.edit { putInt(KEY_HEIGHT, value); putBoolean(KEY_PROFILE_DIRTY, true) }

    var age: Int
        get() = prefs.getInt(KEY_AGE, 21)
        set(value) = prefs.edit { putInt(KEY_AGE, value); putBoolean(KEY_PROFILE_DIRTY, true) }

    /** true = ไมล์, false = กม. */
    var useMiles: Boolean
        get() = prefs.getBoolean(KEY_MILES, false)
        set(value) = prefs.edit { putBoolean(KEY_MILES, value); putBoolean(KEY_PROFILE_DIRTY, true) }

    var weeklyGoalKm: Int
        get() = prefs.getInt(KEY_GOAL, 20)
        set(value) = prefs.edit { putInt(KEY_GOAL, value); putBoolean(KEY_PROFILE_DIRTY, true) }

    /** เพซเป้าหมาย วินาที/กม. */
    var targetPaceSec: Int
        get() = prefs.getInt(KEY_TARGET_PACE, 6 * 60)
        set(value) = prefs.edit { putInt(KEY_TARGET_PACE, value); putBoolean(KEY_PROFILE_DIRTY, true) }

    var autoPause: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PAUSE, value) }

    var kmAlert: Boolean
        get() = prefs.getBoolean(KEY_KM_ALERT, true)
        set(value) = prefs.edit { putBoolean(KEY_KM_ALERT, value) }

    /**
     * ที่อยู่ backend แบบเต็ม เช่น http://10.0.2.2:8080
     * (เวอร์ชันก่อนเก็บแค่ host เช่น "10.0.2.2" → แปลงให้อัตโนมัติ)
     */
    var apiUrl: String
        get() {
            prefs.getString(KEY_API_URL, null)?.let { return it }
            val legacy = prefs.getString(KEY_SERVER, null) ?: return BuildConfig.DEFAULT_API_URL
            return if (legacy.contains("://")) legacy else "http://$legacy${if (legacy.contains(':')) "" else ":8080"}"
        }
        set(value) = prefs.edit { putString(KEY_API_URL, value.trim().trimEnd('/')); remove(KEY_SERVER) }

    /**
     * บัญชีเจ้าของข้อมูลในเครื่อง — คงอยู่จนกว่าจะกดออกจากระบบ
     * (ถ้า token หมดอายุ ผู้ใช้ยังเห็นการวิ่งของตัวเองได้ แค่ซิงก์ไม่ได้จนกว่าจะล็อกอินใหม่)
     */
    var accountUserId: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT, value) }

    /** โปรไฟล์ถูกแก้ในเครื่องและยังไม่ได้ส่งขึ้น server */
    var profileDirty: Boolean
        get() = prefs.getBoolean(KEY_PROFILE_DIRTY, false)
        set(value) = prefs.edit { putBoolean(KEY_PROFILE_DIRTY, value) }

    var leaderboardOptIn: Boolean
        get() = prefs.getBoolean(KEY_LB_OPT_IN, true)
        set(value) = prefs.edit { putBoolean(KEY_LB_OPT_IN, value); putBoolean(KEY_PROFILE_DIRTY, true) }

    var countryCode: String
        get() = prefs.getString(KEY_COUNTRY, "TH") ?: "TH"
        set(value) = prefs.edit { putString(KEY_COUNTRY, value); putBoolean(KEY_PROFILE_DIRTY, true) }

    /** server_time ที่ได้จากการดึงข้อมูลครั้งล่าสุด (ใช้เป็น updated_since รอบหน้า) */
    var lastPull: String?
        get() = prefs.getString(KEY_LAST_PULL, null)
        set(value) = prefs.edit { putString(KEY_LAST_PULL, value) }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC_AT, value) }

    var lastSyncError: String?
        get() = prefs.getString(KEY_LAST_SYNC_ERROR, null)
        set(value) = prefs.edit { putString(KEY_LAST_SYNC_ERROR, value) }

    /** แจ้งเตือนข่าวสาร (สถิติใหม่ ถึงเป้า มีคนแซง มีคนดูลิงก์) ในแถบแจ้งเตือนของเครื่อง */
    var updatesAlert: Boolean
        get() = prefs.getBoolean(KEY_UPDATES_ALERT, true)
        set(value) = prefs.edit { putBoolean(KEY_UPDATES_ALERT, value) }

    /** รายการแจ้งเตือนล่าสุดจาก server (JSON) สำหรับแสดงตอนออฟไลน์ */
    var notificationsCache: String?
        get() = prefs.getString(KEY_NOTIF_CACHE, null)
        set(value) = prefs.edit { putString(KEY_NOTIF_CACHE, value) }

    var unreadNotifications: Int
        get() = prefs.getInt(KEY_NOTIF_UNREAD, 0)
        set(value) = prefs.edit { putInt(KEY_NOTIF_UNREAD, value) }

    /** id ของการแจ้งเตือนที่เคยขึ้นในแถบแจ้งเตือนแล้ว (กันเด้งซ้ำ) */
    var notifiedIds: Set<String>
        get() = prefs.getStringSet(KEY_NOTIFIED, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_NOTIFIED, value.toSet()) }

    /** ผลอันดับโลกล่าสุด (JSON) เก็บไว้แสดงตอนออฟไลน์ */
    fun leaderboardCache(key: String): String? = prefs.getString("lb_$key", null)
    fun saveLeaderboardCache(key: String, json: String) = prefs.edit { putString("lb_$key", json) }

    /** ใส่ค่าโปรไฟล์จาก server (ไม่นับเป็นการแก้ในเครื่อง) */
    fun applyServerProfile(first: String?, last: String?, weight: Int?, height: Int?, birthYear: Int?,
                           miles: Boolean, goal: Int, pace: Int, optIn: Boolean, country: String?) {
        prefs.edit {
            first?.let { putString(KEY_FIRST, it) }
            last?.let { putString(KEY_LAST, it) }
            weight?.let { putInt(KEY_WEIGHT, it) }
            height?.let { putInt(KEY_HEIGHT, it) }
            birthYear?.let { putInt(KEY_AGE, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - it) }
            putBoolean(KEY_MILES, miles); putInt(KEY_GOAL, goal); putInt(KEY_TARGET_PACE, pace)
            putBoolean(KEY_LB_OPT_IN, optIn)
            country?.let { putString(KEY_COUNTRY, it) }
            putBoolean(KEY_PROFILE_DIRTY, false)
        }
    }

    /** ออกจากระบบ: ล้างข้อมูลส่วนตัวและสถานะซิงก์ (ค่าตั้งค่าเครื่อง เช่น ที่อยู่ server ยังอยู่) */
    fun clearAccountData() {
        prefs.edit {
            listOf(KEY_FIRST, KEY_LAST, KEY_WEIGHT, KEY_HEIGHT, KEY_AGE, KEY_LAST_PULL, KEY_LAST_SYNC_AT,
                KEY_LAST_SYNC_ERROR, KEY_PROFILE_DIRTY, KEY_LB_OPT_IN, KEY_ACCOUNT, KEY_NOTIF_CACHE, KEY_NOTIF_UNREAD,
                KEY_NOTIFIED).forEach { remove(it) }
            prefs.all.keys.filter { it.startsWith("lb_") }.forEach { remove(it) }
        }
    }

    var recreateCount: Int
        get() = prefs.getInt(KEY_RECREATE, 0)
        set(value) = prefs.edit { putInt(KEY_RECREATE, value) }

    /** ชื่อเล่นสำหรับทักทาย (คำแรกของชื่อ) */
    val firstName: String get() = firstNameValue.ifEmpty { "นักวิ่ง" }

    val initial: String get() = firstName.take(1)

    companion object {
        private const val PREFS_NAME = "runtrail_prefs"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_FIRST = "first_name"
        private const val KEY_LAST = "last_name"
        private const val KEY_WEIGHT = "weight_kg"
        private const val KEY_HEIGHT = "height_cm"
        private const val KEY_AGE = "age"
        private const val KEY_MILES = "use_miles"
        private const val KEY_GOAL = "weekly_goal_km"
        private const val KEY_TARGET_PACE = "target_pace_sec"
        private const val KEY_AUTO_PAUSE = "auto_pause"
        private const val KEY_KM_ALERT = "km_alert"
        private const val KEY_SERVER = "server_host"
        private const val KEY_API_URL = "api_url"
        private const val KEY_ACCOUNT = "account_user_id"
        private const val KEY_UPDATES_ALERT = "updates_alert"
        private const val KEY_NOTIF_CACHE = "notifications_cache"
        private const val KEY_NOTIF_UNREAD = "notifications_unread"
        private const val KEY_NOTIFIED = "notified_ids"
        private const val KEY_PROFILE_DIRTY = "profile_dirty"
        private const val KEY_LB_OPT_IN = "leaderboard_opt_in"
        private const val KEY_COUNTRY = "country_code"
        private const val KEY_LAST_PULL = "last_pull"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
        private const val KEY_RECREATE = "recreate_count"
    }
}

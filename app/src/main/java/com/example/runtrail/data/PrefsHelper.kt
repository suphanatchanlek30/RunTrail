package com.example.runtrail.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * ค่าตั้งค่าและโปรไฟล์ทั้งหมด (MA09 SharedPreferences)
 */
class PrefsHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDED, value) }

    var firstNameValue: String
        get() = prefs.getString(KEY_FIRST, "ศุภณัฐ") ?: ""
        set(value) = prefs.edit { putString(KEY_FIRST, value.trim()) }

    var lastName: String
        get() = prefs.getString(KEY_LAST, "จันทร์เลข") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST, value.trim()) }

    /** ชื่อ-นามสกุลเต็ม */
    val name: String get() = "$firstNameValue $lastName".trim()

    var weightKg: Int
        get() = prefs.getInt(KEY_WEIGHT, 62)
        set(value) = prefs.edit { putInt(KEY_WEIGHT, value) }

    var heightCm: Int
        get() = prefs.getInt(KEY_HEIGHT, 172)
        set(value) = prefs.edit { putInt(KEY_HEIGHT, value) }

    var age: Int
        get() = prefs.getInt(KEY_AGE, 21)
        set(value) = prefs.edit { putInt(KEY_AGE, value) }

    /** true = ไมล์, false = กม. */
    var useMiles: Boolean
        get() = prefs.getBoolean(KEY_MILES, false)
        set(value) = prefs.edit { putBoolean(KEY_MILES, value) }

    var weeklyGoalKm: Int
        get() = prefs.getInt(KEY_GOAL, 20)
        set(value) = prefs.edit { putInt(KEY_GOAL, value) }

    /** เพซเป้าหมาย วินาที/กม. */
    var targetPaceSec: Int
        get() = prefs.getInt(KEY_TARGET_PACE, 6 * 60)
        set(value) = prefs.edit { putInt(KEY_TARGET_PACE, value) }

    var autoPause: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PAUSE, value) }

    var kmAlert: Boolean
        get() = prefs.getBoolean(KEY_KM_ALERT, true)
        set(value) = prefs.edit { putBoolean(KEY_KM_ALERT, value) }

    var serverHost: String
        get() = prefs.getString(KEY_SERVER, "10.0.2.2") ?: "10.0.2.2"
        set(value) = prefs.edit { putString(KEY_SERVER, value) }

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
        private const val KEY_RECREATE = "recreate_count"
    }
}

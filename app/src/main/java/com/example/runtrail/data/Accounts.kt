package com.example.runtrail.data

import android.content.Context
import com.example.runtrail.App
import com.example.runtrail.net.Session
import com.example.runtrail.sync.SyncManager
import org.json.JSONObject

/**
 * สมัคร / ล็อกอิน / ออกจากระบบ (เรียกจาก background thread)
 *
 * กติกาโปรไฟล์:
 * - สมัครใหม่ (ผู้ใช้ Guest ที่ตั้งโปรไฟล์ไว้แล้ว) → ส่งโปรไฟล์ในเครื่องขึ้น server
 * - ล็อกอินบัญชีเดิม → ใช้โปรไฟล์จาก server
 * - การวิ่งที่บันทึกตอนเป็น Guest จะถูกผูกกับบัญชีที่ล็อกอินแล้วอัปโหลดให้
 * - ล็อกอินบัญชีอื่นบนเครื่องที่มีข้อมูลของบัญชีเดิม → ล้างข้อมูลบัญชีเดิมออกก่อน
 */
object Accounts {

    private val s get() = App.services
    val session: Session? get() = s.sessions.get()
    val isLoggedIn: Boolean get() = session != null

    fun register(context: Context, email: String, password: String, displayName: String, country: String?) {
        val newSession = s.api.register(email.trim(), password, displayName.trim(), country, s.deviceName)
        switchOwner(newSession.userId)
        if (s.prefs.firstNameValue.isBlank()) s.prefs.firstNameValue = displayName.trim()
        country?.let { s.prefs.countryCode = it }
        s.prefs.profileDirty = true
        afterLogin(context, newSession.userId)
    }

    fun login(context: Context, email: String, password: String) {
        val (newSession, user) = s.api.login(email.trim(), password, s.deviceName)
        switchOwner(newSession.userId)
        applyProfile(user)
        afterLogin(context, newSession.userId)
    }

    private fun switchOwner(userId: String) {
        val previous = s.prefs.accountUserId
        if (previous != null && previous != userId) {
            SyncManager.cancelAll(s.context)
            RunRepository.clearAll()
            s.prefs.clearAccountData()
        }
        s.prefs.accountUserId = userId
    }

    private fun afterLogin(context: Context, userId: String) {
        RunRepository.claimGuestRuns(userId)
        SyncManager.schedulePeriodic(context)
        SyncManager.requestSync(context)
    }

    fun applyProfile(u: JSONObject) {
        fun int(k: String) = if (u.isNull(k)) null else u.optInt(k)
        fun str(k: String) = if (u.isNull(k)) null else u.optString(k)
        s.prefs.applyServerProfile(
            first = str("first_name") ?: str("display_name"), last = str("last_name"),
            weight = int("weight_kg"), height = int("height_cm"), birthYear = int("birth_year"),
            miles = u.optBoolean("use_miles"), goal = u.optInt("weekly_goal_km", 20), pace = u.optInt("target_pace_s", 360),
            optIn = u.optBoolean("leaderboard_opt_in", true), country = str("country_code"),
        )
    }

    /** จำนวนรายการที่ยังไม่ได้ส่งขึ้น server (เตือนก่อนออกจากระบบ) */
    fun unsyncedCount(): Int = RunRepository.pendingCount()

    /** ออกจากระบบ: หยุดซิงก์ → ยกเลิก token บน server (ถ้ามีเน็ต) → ล้างข้อมูลในเครื่อง */
    fun logout(context: Context) {
        SyncManager.cancelAll(context)
        synchronized(com.example.runtrail.sync.SyncEngine.LOCK) {
            s.api.logout()
            s.sessions.clear()
            RunRepository.clearAll()
            s.prefs.clearAccountData()
        }
    }

    fun deleteAccount(context: Context) {
        s.api.deleteMe()
        SyncManager.cancelAll(context)
        synchronized(com.example.runtrail.sync.SyncEngine.LOCK) {
            s.sessions.clear()
            RunRepository.clearAll()
            s.prefs.clearAccountData()
        }
    }
}

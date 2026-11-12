package com.example.runtrail.ui.common

import android.app.Activity
import com.example.runtrail.App
import com.example.runtrail.R

/** แก้ที่อยู่ backend (ใช้ทั้งหน้าเข้าสู่ระบบและหน้าตั้งค่า) */
fun Activity.editServerUrl(onSaved: () -> Unit) {
    val prefs = App.services.prefs
    Sheets.input(
        this, R.drawable.ic_server, getString(R.string.setting_server), getString(R.string.setting_server_desc),
        value = prefs.apiUrl, hint = getString(R.string.setting_server_hint),
        validate = { url ->
            if (url.matches(Regex("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?(/.*)?$"))) null
            else getString(R.string.setting_server_invalid)
        },
    ) { url ->
        val current = prefs.apiUrl
        if (url.trimEnd('/') == current) return@input
        if (!com.example.runtrail.data.Accounts.isLoggedIn) {
            prefs.apiUrl = url
            prefs.lastPull = null   // เปลี่ยน server → ดึงข้อมูลใหม่ทั้งหมด
            toast(getString(R.string.toast_saved))
            onSaved()
            return@input
        }
        // ล็อกอินอยู่: ยอมเปลี่ยนเมื่อที่อยู่ใหม่เป็นเซิร์ฟเวอร์เดิมของบัญชีนี้ (เช่น IP ของ Mac เปลี่ยน) — ข้อมูลและ token ใช้ต่อได้
        // ถ้าเป็นเซิร์ฟเวอร์อื่น token และสถานะ "ซิงก์แล้ว" ใช้ไม่ได้ → ให้ซิงก์และออกจากระบบก่อน (AUDIT K1)
        toast(getString(R.string.server_checking))
        runAsync({ App.services.api.sameAccountAt(url) }) { r ->
            if (r.getOrDefault(false)) {
                prefs.apiUrl = url
                toast(getString(R.string.toast_saved))
                com.example.runtrail.sync.SyncManager.requestSync(this)
                onSaved()
            } else {
                Sheets.confirm(
                    this, R.drawable.ic_server, getString(R.string.setting_server), getString(R.string.server_change_logged_in),
                    confirmText = getString(R.string.action_ok), cancelText = null,
                )
            }
        }
    }
}

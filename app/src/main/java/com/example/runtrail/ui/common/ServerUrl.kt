package com.example.runtrail.ui.common

import android.app.Activity
import com.example.runtrail.App
import com.example.runtrail.R

/** แก้ที่อยู่ backend (ใช้ทั้งหน้าเข้าสู่ระบบและหน้าตั้งค่า) */
fun Activity.editServerUrl(onSaved: () -> Unit) {
    val prefs = App.services.prefs
    // ล็อกอินอยู่: token และสถานะ "ซิงก์แล้ว" ผูกกับ server เดิม ถ้าเปลี่ยนตรง ๆ การวิ่งเดิมจะไม่ถูกส่งไป server ใหม่
    // และ token จะใช้ไม่ได้ → ให้ออกจากระบบก่อน (AUDIT K1)
    if (com.example.runtrail.data.Accounts.isLoggedIn) {
        Sheets.confirm(
            this, R.drawable.ic_server, getString(R.string.setting_server), getString(R.string.server_change_logged_in),
            confirmText = getString(R.string.action_ok), cancelText = null,
        )
        return
    }
    Sheets.input(
        this, R.drawable.ic_server, getString(R.string.setting_server), getString(R.string.setting_server_desc),
        value = prefs.apiUrl, hint = getString(R.string.setting_server_hint),
        validate = { url ->
            if (url.matches(Regex("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?(/.*)?$"))) null
            else getString(R.string.setting_server_invalid)
        },
    ) { url ->
        prefs.apiUrl = url
        prefs.lastPull = null   // เปลี่ยน server → ดึงข้อมูลใหม่ทั้งหมด
        toast(getString(R.string.toast_saved))
        onSaved()
    }
}

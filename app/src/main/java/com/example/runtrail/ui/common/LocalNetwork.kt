package com.example.runtrail.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.runtrail.App

/**
 * Android 17+ ต้องได้สิทธิ์ "อุปกรณ์ในเครือข่าย" ก่อนต่อ server ในวง LAN / emulator (10.0.2.2)
 * ขอเฉพาะเมื่อที่อยู่ server เป็นเครือข่ายภายใน และแอปประกาศสิทธิ์นี้ไว้ (เฉพาะ build debug)
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun isLocal(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        if (host == "localhost" || host.endsWith(".local")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 169 && p[1] == 254)
    }

    private fun declared(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(PERMISSION) == true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** ต้องขอสิทธิ์ก่อนใช้งาน server ปัจจุบันหรือไม่ */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && isLocal(App.services.prefs.apiUrl) && declared(context) &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED
}

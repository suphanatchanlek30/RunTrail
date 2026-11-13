package com.example.runtrail.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.runtrail.App

/**
 * Android 17+ requires the local-network permission to reach a LAN or emulator host (10.0.2.2).
 * Requested only for local server addresses and only when the permission is declared (debug builds).
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

    /** Whether the permission must be requested before using the current server. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && isLocal(App.services.prefs.apiUrl) && declared(context) &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED
}

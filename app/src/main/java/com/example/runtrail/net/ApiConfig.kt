package com.example.runtrail.net

import android.net.Uri

/** API endpoint URLs for Volley requests. Built on each call because the server address can change at runtime. */
object ApiConfig {

    /** Current server base URL, e.g. http://10.0.2.2:8080; set at startup. */
    @Volatile var baseUrl: () -> String = { "" }

    val BASE_URL: String get() = baseUrl().trimEnd('/') + "/api/v1"

    val ME get() = "$BASE_URL/me"
    val CHANGE_PASSWORD get() = "$BASE_URL/auth/change-password"
    val NOTIFICATIONS get() = "$BASE_URL/notifications?limit=50"
    val NOTIFICATIONS_READ get() = "$BASE_URL/notifications/read"

    fun leaderboard(metric: String, period: String, scope: String) =
        "$BASE_URL/leaderboard?metric=${Uri.encode(metric)}&period=${Uri.encode(period)}&scope=${Uri.encode(scope)}&limit=100"

    fun userSummary(userId: String) = "$BASE_URL/users/${Uri.encode(userId)}/summary"

    fun share(runUuid: String) = "$BASE_URL/runs/${Uri.encode(runUuid)}/share"
}

package com.example.runtrail.ui.common

import android.app.Activity
import com.example.runtrail.App
import com.example.runtrail.R

/** Dialog to change the backend address, used on sign-in and in settings. */
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
            prefs.lastPull = null   // new server: pull everything again
            toast(getString(R.string.toast_saved))
            onSaved()
            return@input
        }
        // While signed in, only accept the same account's server (e.g. the host's IP changed).
        // A different server would invalidate the tokens and sync state, so the user must sign out first.
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

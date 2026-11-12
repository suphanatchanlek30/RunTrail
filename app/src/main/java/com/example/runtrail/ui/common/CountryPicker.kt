package com.example.runtrail.ui.common

import android.app.Activity
import com.example.runtrail.R
import com.example.runtrail.ui.auth.RegisterActivity

/** รายชื่อประเทศเดียวกับหน้าสมัคร — ใช้ในหน้าตั้งค่าและหน้าอันดับ (AUDIT A7) */
object CountryPicker {

    fun label(code: String?): String =
        RegisterActivity.COUNTRIES.firstOrNull { it.first == code }?.let { "${it.second} (${it.first})" } ?: (code ?: "—")

    fun show(activity: Activity, current: String?, onPick: (String) -> Unit) {
        val list = RegisterActivity.COUNTRIES
        Sheets.choose(
            activity, R.drawable.ic_globe, activity.getString(R.string.auth_country), activity.getString(R.string.auth_country_desc),
            list.map { Sheets.Option(it.second, it.first) },
            selected = list.indexOfFirst { it.first == current },
            alwaysPick = true,
        ) { which -> onPick(list[which].first) }
    }
}

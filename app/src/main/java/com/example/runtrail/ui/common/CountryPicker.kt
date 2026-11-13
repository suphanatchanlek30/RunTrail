package com.example.runtrail.ui.common

import android.app.Activity
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import com.example.runtrail.R
import com.example.runtrail.databinding.DialogCountryBinding
import com.example.runtrail.ui.auth.RegisterActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * เลือกประเทศ — ใช้ในหน้าสมัคร หน้าตั้งค่า และหน้าอันดับ (AUDIT A7)
 * MA08: AutoCompleteTextView + ArrayAdapter + setThreshold(1) — พิมพ์ "ลา" / "la" / "LA" ก็เจอ "LA · ลาว"
 */
object CountryPicker {

    fun label(code: String?): String =
        RegisterActivity.COUNTRIES.firstOrNull { it.first == code }?.let { "${it.second} (${it.first})" } ?: (code ?: "—")

    /** ข้อความในรายการ: รหัสขึ้นก่อน เพื่อให้ตัวกรองของ ArrayAdapter (ขึ้นต้นคำ) จับได้ทั้งรหัสและชื่อ */
    private fun item(code: String, name: String) = "$code · $name"

    fun show(activity: Activity, current: String?, onPick: (String) -> Unit) {
        val list = RegisterActivity.COUNTRIES
        val b = DialogCountryBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.auth_country)
            .setView(b.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        val adapter = ArrayAdapter(activity, R.layout.item_dropdown, list.map { item(it.first, it.second) })
        b.etCountry.setAdapter(adapter)
        b.etCountry.threshold = 1
        list.firstOrNull { it.first == current }?.let { b.etCountry.hint = item(it.first, it.second) }
        // แตะรายการ → ได้ข้อความของแถวนั้น → ตัดเอารหัสประเทศ 2 ตัวหน้า
        b.etCountry.setOnItemClickListener { parent, _, position, _ ->
            val code = (parent.getItemAtPosition(position) as String).substringBefore(" ·")
            dialog.dismiss()
            onPick(code)
        }
        // ยังไม่พิมพ์อะไร → แสดงรายการทั้งหมดให้เลื่อนเลือกได้เลย
        b.etCountry.setOnClickListener { if (b.etCountry.text.isEmpty()) b.etCountry.showDropDown() }
        dialog.setOnShowListener {
            b.etCountry.requestFocus()
            b.etCountry.post { if (!activity.isFinishing) b.etCountry.showDropDown() }
        }
        dialog.show()
    }
}

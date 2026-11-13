package com.example.runtrail.ui.common

import android.app.Activity
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import com.example.runtrail.R
import com.example.runtrail.databinding.DialogCountryBinding
import com.example.runtrail.ui.auth.RegisterActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Country picker with type-ahead search by name or ISO code. */
object CountryPicker {

    fun label(code: String?): String =
        RegisterActivity.COUNTRIES.firstOrNull { it.first == code }?.let { "${it.second} (${it.first})" } ?: (code ?: "—")

    /** Code first, so the adapter's word-prefix filter matches both the code and the name. */
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
        b.etCountry.setOnItemClickListener { parent, _, position, _ ->
            val code = (parent.getItemAtPosition(position) as String).substringBefore(" ·")
            dialog.dismiss()
            onPick(code)
        }
        // Show the full list until the user starts typing.
        b.etCountry.setOnClickListener { if (b.etCountry.text.isEmpty()) b.etCountry.showDropDown() }
        dialog.setOnShowListener {
            b.etCountry.requestFocus()
            b.etCountry.post { if (!activity.isFinishing) b.etCountry.showDropDown() }
        }
        dialog.show()
    }
}

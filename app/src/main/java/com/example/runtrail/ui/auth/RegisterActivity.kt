package com.example.runtrail.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.App
import com.example.runtrail.R
import com.example.runtrail.data.Accounts
import com.example.runtrail.databinding.ActivityRegisterBinding
import com.example.runtrail.ui.common.Sheets
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.common.userMessage
import com.example.runtrail.ui.onboarding.ProfileSetupActivity

/**
 * 21 · สมัครสมาชิก — ประเทศใช้จัดอันดับรายประเทศ
 * การวิ่งที่บันทึกไว้ตอนเป็น Guest จะถูกผูกกับบัญชีใหม่และอัปโหลดให้อัตโนมัติ
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private var country = "TH"
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)

        country = savedInstanceState?.getString(KEY_COUNTRY) ?: App.services.prefs.countryCode
        if (savedInstanceState == null) binding.etName.setText(App.services.prefs.firstNameValue)
        binding.btnBack.setOnClickListener { finish() }
        binding.fieldCountry.setOnClickListener { chooseCountry() }
        binding.btnRegister.setOnClickListener { register() }
        renderCountry()
    }

    private fun renderCountry() {
        binding.fieldCountry.text = COUNTRIES.firstOrNull { it.first == country }?.let { "${it.second} (${it.first})" } ?: country
    }

    private fun chooseCountry() {
        Sheets.choose(
            this, R.drawable.ic_globe, getString(R.string.auth_country), getString(R.string.auth_country_desc),
            COUNTRIES.map { Sheets.Option(it.second, it.first) },
            selected = COUNTRIES.indexOfFirst { it.first == country }.coerceAtLeast(0),
        ) { which ->
            country = COUNTRIES[which].first
            renderCountry()
        }
    }

    private fun register() {
        if (busy) return
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirm = binding.etConfirm.text.toString()
        when {
            name.isEmpty() -> { binding.etName.error = getString(R.string.auth_name_required); return }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> { binding.etEmail.error = getString(R.string.auth_email_invalid); return }
            password.length < 8 -> { binding.etPassword.error = getString(R.string.auth_password_short); return }
            password != confirm -> { binding.etConfirm.error = getString(R.string.auth_password_mismatch); return }
        }
        setBusy(true)
        runAsync({ Accounts.register(applicationContext, email, password, name, country) }) { result ->
            setBusy(false)
            result.onSuccess {
                toast(getString(R.string.auth_registered))
                if (intent.getBooleanExtra(LoginActivity.EXTRA_ONBOARDING, false)) {
                    startActivity(Intent(this, ProfileSetupActivity::class.java))
                }
                finish()
            }.onFailure { e ->
                val fields = (e as? com.example.runtrail.net.ApiException)?.fields.orEmpty()
                if (fields["email"] == "taken") binding.etEmail.error = getString(R.string.auth_email_taken)
                binding.tvError.text = e.userMessage()
                binding.tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
        binding.btnRegister.text = if (value) "" else getString(R.string.auth_register)
        binding.tvError.visibility = View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_COUNTRY, country)
    }

    companion object {
        private const val KEY_COUNTRY = "country"
        val COUNTRIES = listOf(
            "TH" to "ไทย", "LA" to "ลาว", "MM" to "เมียนมา", "KH" to "กัมพูชา", "MY" to "มาเลเซีย",
            "SG" to "สิงคโปร์", "VN" to "เวียดนาม", "ID" to "อินโดนีเซีย", "PH" to "ฟิลิปปินส์",
            "JP" to "ญี่ปุ่น", "KR" to "เกาหลีใต้", "CN" to "จีน", "IN" to "อินเดีย",
            "GB" to "สหราชอาณาจักร", "US" to "สหรัฐอเมริกา", "AU" to "ออสเตรเลีย",
        )
    }
}

package com.example.runtrail.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.App
import com.example.runtrail.R
import com.example.runtrail.data.Accounts
import com.example.runtrail.databinding.ActivityLoginBinding
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.editServerUrl
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.showWakeHintIfSlow
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.common.userMessage
import com.example.runtrail.ui.onboarding.ProfileSetupActivity

/**
 * 20 · เข้าสู่ระบบ
 * - ล็อกอินครั้งแรกต้องใช้เน็ต หลังจากนั้นเปิดแอปออฟไลน์ได้ตลอด
 * - "ใช้งานแบบออฟไลน์ก่อน" = Guest: วิ่งและบันทึกได้ครบ อัปโหลดให้เมื่อล็อกอินภายหลัง
 * - เปิดจากขั้นตอนเริ่มใช้งาน (EXTRA_ONBOARDING) → ไปหน้าโปรไฟล์ต่อ, เปิดจากที่อื่น → กลับหน้าเดิม
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var onboarding = false
    private var busy = false

    private val askLocalNetwork = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) toast(getString(R.string.local_network_denied)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)
        onboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogin.setOnClickListener { login() }
        binding.etPassword.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { login(); true } else false
        }
        binding.btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java).putExtra(EXTRA_ONBOARDING, onboarding))
            finish()
        }
        binding.btnGuest.visibility = if (onboarding) View.VISIBLE else View.GONE
        binding.btnGuest.setOnClickListener { next() }
        binding.btnServer.setOnClickListener { editServerUrl { renderServer(); askLocalNetworkIfNeeded() } }
        renderServer()
        askLocalNetworkIfNeeded()
        App.services.wakeServer()   // ระหว่างผู้ใช้พิมพ์อีเมล/รหัสผ่าน เซิร์ฟเวอร์ที่หลับอยู่จะได้ตื่น
    }

    private fun askLocalNetworkIfNeeded() {
        if (com.example.runtrail.ui.common.LocalNetwork.needsPermission(this)) {
            askLocalNetwork.launch(com.example.runtrail.ui.common.LocalNetwork.PERMISSION)
        }
    }

    private fun renderServer() {
        binding.btnServer.text = getString(R.string.auth_server, App.services.prefs.apiUrl)
    }

    private fun login() {
        if (busy) return
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        when {
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> { binding.etEmail.error = getString(R.string.auth_email_invalid); return }
            password.isEmpty() -> { binding.etPassword.error = getString(R.string.auth_password_required); return }
        }
        setBusy(true)
        showWakeHintIfSlow { busy }
        runAsync({ Accounts.login(applicationContext, email, password) }) { result ->
            setBusy(false)
            result.onSuccess {
                toast(getString(R.string.auth_welcome, Accounts.session?.displayName ?: ""))
                next()
            }.onFailure { showError(it.userMessage()) }
        }
    }

    private fun next() {
        if (onboarding) startActivity(Intent(this, ProfileSetupActivity::class.java))
        finish()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
        binding.btnLogin.text = if (value) "" else getString(R.string.auth_login)
        binding.tvError.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_ONBOARDING = "onboarding"
    }
}

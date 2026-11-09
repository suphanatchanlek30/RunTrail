package com.example.runtrail.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.databinding.ActivityPermissionsBinding
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast

/**
 * 02 · Permissions — อธิบายเหตุผลก่อนขอ runtime permission (ตำแหน่ง · กิจกรรมทางกาย · แจ้งเตือน)
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsBinding
    private var goNextAfterLocation = false

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        refreshState()
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!granted) {
            toast(getString(R.string.perm_location_denied))
        } else if (goNextAfterLocation) {
            openProfile()
        }
        goNextAfterLocation = false
    }

    private val singleLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSkip.setOnClickListener { openProfile() }
        binding.cardLocation.setOnClickListener { requestLocation() }
        binding.cardActivity.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                singleLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        binding.btnAllowNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.btnNext.setOnClickListener {
            if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                openProfile()
            } else {
                goNextAfterLocation = true
                requestLocation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun requestLocation() {
        locationLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    private fun refreshState() {
        binding.checkLocation.isActivated = has(Manifest.permission.ACCESS_FINE_LOCATION)
        binding.checkActivity.isActivated = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            has(Manifest.permission.ACTIVITY_RECOGNITION)
        val notifyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            has(Manifest.permission.POST_NOTIFICATIONS)
        binding.btnAllowNotify.visibility = if (notifyGranted) View.GONE else View.VISIBLE
        binding.checkNotify.visibility = if (notifyGranted) View.VISIBLE else View.GONE
        binding.checkNotify.isActivated = true
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openProfile() {
        startActivity(Intent(this, ProfileSetupActivity::class.java))
    }
}

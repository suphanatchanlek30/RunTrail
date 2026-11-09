package com.example.runtrail.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.databinding.ActivitySettingsBinding
import com.example.runtrail.databinding.ViewSettingRowBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.sensor.SensorHelper
import com.example.runtrail.ui.common.BottomNav
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.onboarding.ProfileSetupActivity
import com.example.runtrail.ui.sensor.SensorCheckActivity
import com.example.runtrail.ui.common.Sheets

/**
 * 17 · Settings — ค่าทั้งหมดเก็บใน SharedPreferences (MA09)
 * "ทดสอบคืนสถานะหน้าจอ" เรียก recreate() แล้วนับจำนวนครั้งที่ Activity ถูกสร้างใหม่ผ่าน onSaveInstanceState
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsHelper
    private var createdCount = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.content.applySystemBarsPadding(top = true)
        prefs = PrefsHelper(this)
        createdCount = (savedInstanceState?.getInt(KEY_CREATED) ?: 0) + 1

        BottomNav.setup(this, binding.navContainer.bottomNav, R.id.nav_settings)

        setupRow(binding.rowUnit, R.drawable.ic_ruler, R.string.setting_unit)
        setupRow(binding.rowGoal, R.drawable.ic_target, R.string.setting_weekly_goal)
        setupRow(binding.rowPace, R.drawable.ic_gauge, R.string.setting_target_pace)
        setupRow(binding.rowAutoPause, R.drawable.ic_pause, R.string.setting_auto_pause, toggle = true)
        setupRow(binding.rowKmAlert, R.drawable.ic_bell, R.string.setting_km_alert, toggle = true)
        setupRow(binding.rowServer, R.drawable.ic_server, R.string.setting_server)
        setupRow(binding.rowSensors, R.drawable.ic_cpu, R.string.setting_sensors)
        setupRow(binding.rowRecreate, R.drawable.ic_rotate, R.string.setting_recreate)

        binding.cardProfile.setOnClickListener {
            startActivity(Intent(this, ProfileSetupActivity::class.java).putExtra(ProfileSetupActivity.EXTRA_EDIT, true))
        }
        binding.rowUnit.root.setOnClickListener { chooseUnit() }
        binding.rowGoal.root.setOnClickListener { chooseGoal() }
        binding.rowPace.root.setOnClickListener { choosePace() }
        binding.rowAutoPause.root.setOnClickListener { binding.rowAutoPause.swToggle.toggle() }
        binding.rowKmAlert.root.setOnClickListener { binding.rowKmAlert.swToggle.toggle() }
        binding.rowAutoPause.swToggle.setOnCheckedChangeListener { _, checked -> prefs.autoPause = checked }
        binding.rowKmAlert.swToggle.setOnCheckedChangeListener { _, checked -> prefs.kmAlert = checked }
        binding.rowServer.root.setOnClickListener { editServer() }
        binding.rowSensors.root.setOnClickListener { startActivity(Intent(this, SensorCheckActivity::class.java)) }
        binding.rowRecreate.root.setOnClickListener { recreate() }
    }

    override fun onResume() {
        super.onResume()
        binding.navContainer.bottomNav.selectedItemId = R.id.nav_settings
        render()
    }

    private fun setupRow(row: ViewSettingRowBinding, icon: Int, title: Int, toggle: Boolean = false) {
        row.ivIcon.setImageResource(icon)
        row.tvTitle.setText(title)
        row.tvValue.visibility = if (toggle) View.GONE else View.VISIBLE
        row.ivChevron.visibility = if (toggle) View.GONE else View.VISIBLE
        row.swToggle.visibility = if (toggle) View.VISIBLE else View.GONE
    }

    private fun render() {
        val miles = prefs.useMiles
        binding.tvAvatar.text = prefs.initial
        binding.tvName.text = prefs.name
        binding.tvBody.text = getString(R.string.settings_body, prefs.weightKg, prefs.heightCm, prefs.age)
        binding.rowUnit.tvValue.text = Fmt.unit(miles)
        binding.rowGoal.tvValue.text = "${prefs.weeklyGoalKm} ${Fmt.unit(miles)}"
        binding.rowPace.tvValue.text = Fmt.pace(prefs.targetPaceSec)
        binding.rowAutoPause.swToggle.isChecked = prefs.autoPause
        binding.rowKmAlert.swToggle.isChecked = prefs.kmAlert
        binding.rowServer.tvValue.text = prefs.serverHost
        binding.rowSensors.tvValue.text = "${SensorHelper.countAvailable(this)}/${SensorHelper.specs.size}"
        binding.rowRecreate.tvValue.text = getString(R.string.setting_recreate_value, createdCount)
    }

    private fun chooseUnit() {
        Sheets.choose(
            this, R.drawable.ic_ruler, getString(R.string.setting_unit), getString(R.string.sheet_unit_sub),
            listOf(
                Sheets.Option(getString(R.string.unit_km_full), getString(R.string.sheet_unit_km_hint)),
                Sheets.Option(getString(R.string.unit_mile_full), getString(R.string.sheet_unit_mile_hint)),
            ),
            selected = if (prefs.useMiles) 1 else 0,
        ) { which ->
            prefs.useMiles = which == 1
            render()
        }
    }

    private fun chooseGoal() {
        val values = (5..50 step 5).toList()
        val unit = Fmt.unit(prefs.useMiles)
        Sheets.choose(
            this, R.drawable.ic_target, getString(R.string.setting_weekly_goal), getString(R.string.sheet_goal_sub),
            values.map { Sheets.Option(it.toString(), "$unit/สัปดาห์") },
            selected = values.indexOf(prefs.weeklyGoalKm).coerceAtLeast(0),
            columns = 3,
        ) { which ->
            prefs.weeklyGoalKm = values[which]
            render()
        }
    }

    private fun choosePace() {
        val values = (270..480 step 15).toList()
        Sheets.choose(
            this, R.drawable.ic_gauge, getString(R.string.setting_target_pace), getString(R.string.sheet_pace_sub),
            values.map { Sheets.Option(Fmt.pace(it), paceLevel(it)) },
            selected = values.indexOf(prefs.targetPaceSec).coerceAtLeast(0),
            columns = 3,
        ) { which ->
            prefs.targetPaceSec = values[which]
            render()
        }
    }

    /** คำอธิบายระดับเพซสั้น ๆ ใต้ตัวเลข */
    private fun paceLevel(sec: Int): String = getString(
        when {
            sec < 330 -> R.string.pace_level_fast
            sec < 390 -> R.string.pace_level_steady
            else -> R.string.pace_level_easy
        },
    )

    private fun editServer() {
        Sheets.input(
            this, R.drawable.ic_server, getString(R.string.setting_server), getString(R.string.setting_server_desc),
            value = prefs.serverHost, hint = getString(R.string.setting_server_hint),
            validate = { host ->
                if (host.matches(Regex("^[A-Za-z0-9.-]+(:\\d{1,5})?$"))) null else getString(R.string.setting_server_invalid)
            },
        ) { host ->
            prefs.serverHost = host
            toast(getString(R.string.toast_saved))
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CREATED, createdCount)
    }

    companion object {
        private const val KEY_CREATED = "created_count"
    }
}

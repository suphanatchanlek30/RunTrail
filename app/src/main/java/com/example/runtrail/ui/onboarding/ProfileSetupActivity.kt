package com.example.runtrail.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.databinding.ActivityProfileSetupBinding
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.home.MainActivity

/**
 * 03 · Profile & Goal — ชื่อ-นามสกุล · น้ำหนัก/ส่วนสูง/อายุ/หน่วย + เป้าหมายรายสัปดาห์
 * - เก็บลง SharedPreferences (MA09)
 * - หมุนจอแล้วค่าที่เลือกไม่หาย (onSaveInstanceState, MA09)
 * - เปิดจากหน้าตั้งค่าด้วย EXTRA_EDIT = true เพื่อใช้เป็นหน้าแก้ไขโปรไฟล์
 */
class ProfileSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSetupBinding
    private lateinit var prefs: PrefsHelper
    private var useMiles = false
    private var goalKm = 20
    private var editMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityProfileSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)

        prefs = PrefsHelper(this)
        editMode = intent.getBooleanExtra(EXTRA_EDIT, false)

        if (savedInstanceState == null) {
            binding.etFirstName.setText(prefs.firstNameValue)
            binding.etLastName.setText(prefs.lastName)
            binding.etWeight.setText(prefs.weightKg.toString())
            binding.etHeight.setText(prefs.heightCm.toString())
            binding.etAge.setText(prefs.age.toString())
            useMiles = prefs.useMiles
            goalKm = prefs.weeklyGoalKm
        } else {
            useMiles = savedInstanceState.getBoolean(KEY_MILES)
            goalKm = savedInstanceState.getInt(KEY_GOAL)
        }

        if (editMode) {
            binding.tvStep.visibility = View.INVISIBLE
            binding.progressStep.visibility = View.GONE
            binding.tvTitle.setText(R.string.profile_edit_title)
            binding.btnDone.setText(R.string.action_save)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.segKm.setOnClickListener { useMiles = false; renderUnit() }
        binding.segMi.setOnClickListener { useMiles = true; renderUnit() }

        binding.seekGoal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { goalKm = progress + MIN_GOAL; renderGoal() }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        mapOf(binding.chip10 to 10, binding.chip15 to 15, binding.chip20 to 20, binding.chip30 to 30)
            .forEach { (chip, km) -> chip.setOnClickListener { goalKm = km; renderGoal() } }

        binding.btnDone.setOnClickListener { save() }

        renderUnit()
        renderGoal()
    }

    private fun renderUnit() {
        binding.segKm.isSelected = !useMiles
        binding.segMi.isSelected = useMiles
    }

    private fun renderGoal() {
        binding.tvGoal.text = goalKm.toString()
        binding.seekGoal.progress = goalKm - MIN_GOAL
        val chips: Map<TextView, Int> =
            mapOf(binding.chip10 to 10, binding.chip15 to 15, binding.chip20 to 20, binding.chip30 to 30)
        chips.forEach { (chip, km) -> chip.isSelected = km == goalKm }
    }

    private fun save() {
        val first = binding.etFirstName.text.toString().trim()
        if (first.isEmpty()) {
            binding.etFirstName.error = getString(R.string.error_first_name)
            binding.etFirstName.requestFocus()
            return
        }
        val weight = binding.etWeight.text.toString().toIntOrNull()
        val height = binding.etHeight.text.toString().toIntOrNull()
        val age = binding.etAge.text.toString().toIntOrNull()
        when {
            weight == null || weight !in 25..250 -> { binding.etWeight.error = getString(R.string.error_weight); return }
            height == null || height !in 100..250 -> { binding.etHeight.error = getString(R.string.error_height); return }
            age == null || age !in 8..100 -> { binding.etAge.error = getString(R.string.error_age); return }
        }
        prefs.firstNameValue = first
        prefs.lastName = binding.etLastName.text.toString()
        prefs.weightKg = weight!!
        prefs.heightCm = height!!
        prefs.age = age!!
        prefs.useMiles = useMiles
        prefs.weeklyGoalKm = goalKm

        if (editMode) {
            toast(getString(R.string.toast_saved))
            finish()
        } else {
            prefs.onboarded = true
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MILES, useMiles)
        outState.putInt(KEY_GOAL, goalKm)
    }

    companion object {
        const val EXTRA_EDIT = "edit_mode"
        private const val KEY_MILES = "use_miles"
        private const val KEY_GOAL = "goal_km"
        private const val MIN_GOAL = 5
    }
}

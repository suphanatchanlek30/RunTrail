package com.example.runtrail.ui.edit

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.databinding.ActivityAddEditRunBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.ui.common.ImageStore
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setStartIcon
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.common.Sheets
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 12 · Add / Edit Run (MA10 CRUD)
 * - id > 0 → แก้ไข (update) · ไม่มี id → เพิ่มการวิ่งย้อนหลัง (insert)
 * - รูปจากกล้อง (TakePicturePreview) / แกลเลอรี (GetContent) → แก้ทิศ EXIF → ย่อ → JPEG 80%
 * - แก้แค่ข้อความโดยไม่เปลี่ยนรูปได้ · หมุนจอค่าไม่หาย (onSaveInstanceState)
 */
class AddEditRunActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEditRunBinding
    private lateinit var prefs: PrefsHelper
    private var editing: Run? = null
    private var startTime = System.currentTimeMillis()
    private var photoPath: String? = null
    private var photoChanged = false

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
        if (bmp != null) setPhoto(ImageStore.save(this, bmp))
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val path = ImageStore.saveFromUri(this, uri)
            if (path != null) setPhoto(path) else toast(getString(R.string.edit_photo_error))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityAddEditRunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)
        prefs = PrefsHelper(this)

        val id = intent.getLongExtra(RunDetailActivity.EXTRA_RUN_ID, 0L)
        editing = if (id > 0) RunRepository.getById(id) else null
        val isEdit = editing != null

        binding.tvTitle.setText(if (isEdit) R.string.edit_title_edit else R.string.edit_title_add)
        binding.btnSave.setText(if (isEdit) R.string.edit_save_changes else R.string.edit_save_new)
        binding.groupManual.visibility = if (isEdit) View.GONE else View.VISIBLE
        binding.rowLocked.visibility = if (isEdit) View.VISIBLE else View.GONE
        binding.tvDistanceLabel.text = getString(R.string.edit_distance_unit, Fmt.unit(prefs.useMiles))

        binding.photoPreview.clipToOutline = true
        binding.map.routeTop = 0.02f
        binding.map.routeBottom = 0.98f
        editing?.let { binding.map.setRoute(RunRepository.route(it)) }

        if (savedInstanceState != null) {
            startTime = savedInstanceState.getLong(KEY_START)
            photoPath = savedInstanceState.getString(KEY_PHOTO)
            photoChanged = savedInstanceState.getBoolean(KEY_PHOTO_CHANGED)
        } else {
            editing?.let {
                binding.etName.setText(it.title)
                binding.etNote.setText(it.note.orEmpty())
                startTime = it.startTime
                photoPath = it.photoUri
            }
        }
        renderPhoto()
        renderDate()

        binding.btnCamera.setOnClickListener { takePhoto.launch(null) }
        binding.btnGallery.setOnClickListener { pickPhoto.launch("image/*") }
        binding.photoChip.setOnClickListener { pickPhoto.launch("image/*") }
        binding.fieldDate.setOnClickListener { pickDate() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnSaveTop.setOnClickListener { save() }
        binding.btnClose.setOnClickListener { confirmClose() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmClose()
        })
    }

    private fun setPhoto(path: String) {
        photoPath = path
        photoChanged = true
        renderPhoto()
    }

    private fun renderPhoto() {
        val bmp = ImageStore.load(photoPath)
        binding.ivPhoto.setImageBitmap(bmp)
        binding.ivPhoto.visibility = if (bmp != null) View.VISIBLE else View.GONE
        if (bmp != null) {
            binding.tvPhotoStatus.setText(if (photoChanged) R.string.edit_photo_processed else R.string.edit_photo_current)
            binding.tvPhotoStatus.setStartIcon(R.drawable.ic_check, 14f, R.color.run_lime)
        } else {
            binding.tvPhotoStatus.setText(R.string.edit_photo_empty)
            binding.tvPhotoStatus.setCompoundDrawablesRelative(null, null, null, null)
        }
    }

    private fun renderDate() {
        binding.tvDate.text = Fmt.detailDate(startTime, null)
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = startTime }
        DatePickerDialog(this, R.style.ThemeOverlay_RunTrail_Picker, { _, y, m, d ->
            TimePickerDialog(this, R.style.ThemeOverlay_RunTrail_Picker, { _, h, min ->
                c.set(y, m, d, h, min)
                if (c.timeInMillis > System.currentTimeMillis()) {
                    toast(getString(R.string.edit_future_error))
                } else {
                    startTime = c.timeInMillis
                    renderDate()
                }
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun save() {
        val title = binding.etName.text.toString().trim()
        if (title.isEmpty()) {
            binding.etName.error = getString(R.string.edit_name_error)
            binding.etName.requestFocus()
            return
        }
        val note = binding.etNote.text.toString().trim().ifEmpty { null }
        val current = editing
        if (current != null) {
            RunRepository.update(current.copy(title = title, note = note, startTime = startTime, photoUri = photoPath))
            toast(getString(R.string.toast_saved))
            finish()
            return
        }

        val distInput = binding.etDistance.text.toString().toDoubleOrNull()
        val minutes = binding.etDuration.text.toString().toLongOrNull()
        if (distInput == null || distInput <= 0 || distInput > 100) {
            binding.etDistance.error = getString(R.string.edit_distance_error); return
        }
        if (minutes == null || minutes <= 0 || minutes > 1440) {
            binding.etDuration.error = getString(R.string.edit_duration_error); return
        }
        val meters = distInput * if (prefs.useMiles) 1609.344 else 1000.0
        val seconds = minutes * 60
        val run = Run(
            title = title, note = note, startTime = startTime, endTime = startTime + seconds * 1000,
            distanceM = meters, movingTimeS = seconds,
            calories = RunCalculator.calories(meters, seconds, prefs.weightKg).roundToInt().toDouble(),
            elevationGainM = (meters / 250).roundToInt(), photoUri = photoPath,
            routeSeed = Random.nextInt(20, 400),
        )
        val id = RunRepository.insert(run)
        toast(getString(R.string.toast_added))
        startActivity(Intent(this, RunDetailActivity::class.java).putExtra(RunDetailActivity.EXTRA_RUN_ID, id))
        finish()
    }

    private fun hasChanges(): Boolean {
        val e = editing
        val title = binding.etName.text.toString().trim()
        val note = binding.etNote.text.toString().trim()
        return if (e == null) {
            title.isNotEmpty() || note.isNotEmpty() || photoPath != null ||
                binding.etDistance.text.isNotEmpty() || binding.etDuration.text.isNotEmpty()
        } else {
            title != e.title || note != e.note.orEmpty() || startTime != e.startTime || photoPath != e.photoUri
        }
    }

    private fun confirmClose() {
        if (!hasChanges()) { finish(); return }
        Sheets.confirm(
            this, R.drawable.ic_info, getString(R.string.edit_discard_title), getString(R.string.edit_discard_body),
            confirmText = getString(R.string.edit_discard), cancelText = getString(R.string.edit_keep_editing),
            tone = Sheets.Tone.DANGER,
        ) { finish() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_START, startTime)
        outState.putString(KEY_PHOTO, photoPath)
        outState.putBoolean(KEY_PHOTO_CHANGED, photoChanged)
    }

    companion object {
        private const val KEY_START = "start"
        private const val KEY_PHOTO = "photo"
        private const val KEY_PHOTO_CHANGED = "photo_changed"
    }
}

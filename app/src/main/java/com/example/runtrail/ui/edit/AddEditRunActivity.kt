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
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setStartIcon
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.common.Sheets
import java.util.Calendar
import kotlin.math.roundToInt

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

    private var pendingPhoto: java.io.File? = null

    /** รูปที่สร้างขึ้นระหว่างแก้ไขหน้านี้ — ไฟล์ที่ไม่ได้ใช้จะถูกลบตอนออก (ไม่ทิ้งไฟล์ค้าง) */
    private val sessionPhotos = mutableListOf<String>()

    /** กล้องแบบเต็มความละเอียดผ่าน FileProvider แล้วย่อ/แก้ทิศตาม EXIF */
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null) importPhoto(Uri.fromFile(file), deleteAfter = file) else file?.delete()
    }

    /** ย่อ/แก้ทิศรูปบน background thread (รูปจากกล้องอาจใหญ่หลายสิบ MB, AUDIT A3) */
    private fun importPhoto(uri: Uri, deleteAfter: java.io.File? = null) {
        toast(getString(R.string.photo_processing))
        runAsync({ ImageStore.saveFromUri(applicationContext, uri).also { deleteAfter?.delete() } }) { r ->
            val path = r.getOrNull()
            if (path != null) { sessionPhotos += path; setPhoto(path) } else toast(getString(R.string.edit_photo_error))
        }
    }

    private fun openCamera() {
        val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
        val file = java.io.File(dir, "edit_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        try {
            takePhoto.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file))
        } catch (e: android.content.ActivityNotFoundException) {
            pendingPhoto = null
            toast(getString(R.string.live_no_camera))
        }
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) importPhoto(uri)
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
            pendingPhoto = savedInstanceState.getString(KEY_PENDING)?.let { java.io.File(it) }
            savedInstanceState.getStringArrayList(KEY_SESSION)?.let { sessionPhotos += it }
        } else {
            editing?.let {
                binding.etName.setText(it.title)
                binding.etNote.setText(it.note.orEmpty())
                binding.ratingFeel.rating = it.feel.toFloat()
                startTime = it.startTime
                photoPath = it.photoUri
            }
        }
        renderPhoto()
        renderDate()
        renderFeel()
        binding.ratingFeel.setOnRatingBarChangeListener { _, _, _ -> renderFeel() }

        binding.btnCamera.setOnClickListener { openCamera() }
        binding.btnGallery.setOnClickListener { pickPhoto.launch("image/*") }
        binding.photoChip.setOnClickListener { pickPhoto.launch("image/*") }
        binding.btnRemovePhoto.setOnClickListener {
            photoPath = null
            photoChanged = true
            renderPhoto()
            toast(getString(R.string.edit_photo_removed))
        }
        // การวิ่งที่บันทึกด้วย GPS มีเวลาในทุกจุดอยู่แล้ว จึงแก้วันเวลาไม่ได้ (server ก็ไม่ยอมให้แก้)
        val dateLocked = editing?.isGps == true
        binding.fieldDate.alpha = if (dateLocked) 0.5f else 1f
        binding.fieldDate.setOnClickListener {
            if (dateLocked) toast(getString(R.string.edit_date_locked)) else pickDate()
        }
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
        binding.btnRemovePhoto.visibility = if (bmp != null) View.VISIBLE else View.GONE
        if (bmp != null) {
            binding.tvPhotoStatus.setText(if (photoChanged) R.string.edit_photo_processed else R.string.edit_photo_current)
            binding.tvPhotoStatus.setStartIcon(R.drawable.ic_check, 14f, R.color.run_lime)
        } else {
            binding.tvPhotoStatus.setText(R.string.edit_photo_empty)
            binding.tvPhotoStatus.setCompoundDrawablesRelative(null, null, null, null)
        }
    }

    /** ข้อความใต้ดาว: 0 = ยังไม่ให้ · 1–5 = ระดับความรู้สึก */
    private fun renderFeel() {
        binding.tvFeel.text = feelLabel(this, binding.ratingFeel.rating.toInt())
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
        val feel = binding.ratingFeel.rating.toInt()          // RatingBar.getRating() คืน float → ปัดเป็นจำนวนดาว
        val current = editing
        if (current != null) {
            val photoSync = when {
                !photoChanged -> current.photoSync
                photoPath != null -> com.example.runtrail.data.PhotoSync.PENDING
                // ลบรูปที่ขึ้น server แล้ว → ให้ SyncEngine ส่งคำสั่งลบรูป (AUDIT A9)
                current.onServer && current.photoSync == com.example.runtrail.data.PhotoSync.SYNCED ->
                    com.example.runtrail.data.PhotoSync.DELETE
                else -> com.example.runtrail.data.PhotoSync.NONE
            }
            if (photoChanged && current.photoUri != photoPath) ImageStore.delete(this, current.photoUri)
            cleanupSessionPhotos(keep = photoPath)
            RunRepository.update(current.copy(title = title, note = note, feel = feel, startTime = startTime, photoUri = photoPath,
                endTime = current.endTime?.let { it + (startTime - current.startTime) }, photoSync = photoSync))
            com.example.runtrail.sync.SyncManager.requestSync(this)
            setResult(RESULT_OK)          // MA10: บอกหน้าที่เปิดมาว่าบันทึกแล้ว (หน้ารายละเอียดจะโหลดใหม่)
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
            title = title, note = note, feel = feel, startTime = startTime, endTime = startTime + seconds * 1000,
            distanceM = meters, movingTimeS = seconds,
            calories = RunCalculator.calories(meters, seconds, prefs.weightKg).roundToInt().toDouble(),
            elevationGainM = 0, photoUri = photoPath, source = com.example.runtrail.data.RunSource.MANUAL,
            photoSync = if (photoPath != null) com.example.runtrail.data.PhotoSync.PENDING else com.example.runtrail.data.PhotoSync.NONE,
        )
        cleanupSessionPhotos(keep = photoPath)
        val id = RunRepository.insert(run)
        com.example.runtrail.sync.SyncManager.requestSync(this)
        toast(getString(R.string.toast_added))
        startActivity(Intent(this, RunDetailActivity::class.java).putExtra(RunDetailActivity.EXTRA_RUN_ID, id))
        finish()
    }

    private fun hasChanges(): Boolean {
        val e = editing
        val title = binding.etName.text.toString().trim()
        val note = binding.etNote.text.toString().trim()
        return if (e == null) {
            title.isNotEmpty() || note.isNotEmpty() || photoPath != null || binding.ratingFeel.rating > 0 ||
                binding.etDistance.text.isNotEmpty() || binding.etDuration.text.isNotEmpty()
        } else {
            title != e.title || note != e.note.orEmpty() || startTime != e.startTime || photoPath != e.photoUri ||
                binding.ratingFeel.rating.toInt() != e.feel
        }
    }

    private fun confirmClose() {
        if (!hasChanges()) { cleanupSessionPhotos(keep = null); finish(); return }
        Sheets.confirm(
            this, R.drawable.ic_info, getString(R.string.edit_discard_title), getString(R.string.edit_discard_body),
            confirmText = getString(R.string.edit_discard), cancelText = getString(R.string.edit_keep_editing),
            tone = Sheets.Tone.DANGER,
        ) { cleanupSessionPhotos(keep = null); finish() }
    }

    /** ลบรูปที่ถ่าย/เลือกในหน้านี้แต่ไม่ได้ใช้ */
    private fun cleanupSessionPhotos(keep: String?) {
        sessionPhotos.filter { it != keep }.forEach { ImageStore.delete(this, it) }
        sessionPhotos.clear()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_START, startTime)
        outState.putString(KEY_PHOTO, photoPath)
        outState.putBoolean(KEY_PHOTO_CHANGED, photoChanged)
        outState.putString(KEY_PENDING, pendingPhoto?.absolutePath)
        outState.putStringArrayList(KEY_SESSION, ArrayList(sessionPhotos))
    }

    companion object {
        /** คำอธิบายระดับความรู้สึก (ใช้ทั้งหน้านี้และหน้าสรุปผล) */
        fun feelLabel(context: android.content.Context, stars: Int): String = context.getString(
            when (stars) {
                1 -> R.string.feel_1
                2 -> R.string.feel_2
                3 -> R.string.feel_3
                4 -> R.string.feel_4
                5 -> R.string.feel_5
                else -> R.string.feel_none
            },
        )

        private const val KEY_START = "start"
        private const val KEY_PHOTO = "photo"
        private const val KEY_PHOTO_CHANGED = "photo_changed"
        private const val KEY_PENDING = "pending_photo"
        private const val KEY_SESSION = "session_photos"
    }
}

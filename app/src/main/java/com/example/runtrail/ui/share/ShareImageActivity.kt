package com.example.runtrail.ui.share

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.runtrail.App
import com.example.runtrail.R
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.databinding.ActivityShareImageBinding
import com.example.runtrail.domain.ShareImageLayout.Format
import com.example.runtrail.ui.common.ImageStore
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.detail.RunDetailActivity
import java.io.File

/**
 * Builds a shareable run image offline: run photo or route on dark, 9:16 or 1:1.
 * Saves to Pictures/RunTrail or shares to other apps.
 */
class ShareImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareImageBinding
    private lateinit var run: Run
    private var photoPath: String? = null
    private var usePhoto = false
    private var format = Format.STORY
    private var image: Bitmap? = null
    private var renderId = 0

    /** Lets the user pick a background photo for this image only, when the run has none. */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        binding.progress.visibility = View.VISIBLE
        runAsync({ ImageStore.saveFromUri(applicationContext, uri) }) { r ->
            val path = r.getOrNull()
            if (path == null) {
                binding.progress.visibility = View.GONE
                toast(getString(R.string.edit_photo_error))
            } else {
                photoPath = path
                usePhoto = true
                update()
            }
        }
    }

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) save() else toast(getString(R.string.share_image_save_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityShareImageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)

        run = RunRepository.getById(intent.getLongExtra(RunDetailActivity.EXTRA_RUN_ID, 0)) ?: run { finish(); return }
        photoPath = savedInstanceState?.getString(KEY_PHOTO) ?: run.photoUri?.takeIf { File(it).exists() }
        usePhoto = savedInstanceState?.getBoolean(KEY_USE_PHOTO) ?: (photoPath != null)
        format = savedInstanceState?.getString(KEY_FORMAT)?.let { Format.valueOf(it) } ?: Format.STORY

        binding.btnBack.setOnClickListener { finish() }
        binding.segPhoto.setOnClickListener {
            if (photoPath == null) pickPhoto.launch("image/*") else { usePhoto = true; update() }
        }
        binding.segRoute.setOnClickListener { usePhoto = false; update() }
        binding.segStory.setOnClickListener { format = Format.STORY; update() }
        binding.segSquare.setOnClickListener { format = Format.SQUARE; update() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnShare.setOnClickListener { share() }
        update()
    }

    /** Re-renders off the main thread whenever an option changes. */
    private fun update() {
        binding.segPhoto.isSelected = usePhoto
        binding.segRoute.isSelected = !usePhoto
        binding.segStory.isSelected = format == Format.STORY
        binding.segSquare.isSelected = format == Format.SQUARE
        binding.segPhoto.text = getString(if (photoPath == null) R.string.share_image_bg_pick else R.string.share_image_bg_photo)
        binding.progress.visibility = View.VISIBLE
        setButtons(false)
        val id = ++renderId
        val path = if (usePhoto) photoPath else null
        val f = format
        val miles = App.services.prefs.useMiles
        runAsync({
            val photo = path?.let { ImageStore.load(it) }
            ShareImageRenderer(this).render(run, photo, f, miles)
        }) { result ->
            if (id != renderId) return@runAsync   // options changed while rendering
            binding.progress.visibility = View.GONE
            result.onSuccess {
                image = it
                binding.ivPreview.setImageBitmap(it)
                setButtons(true)
            }.onFailure { toast(getString(R.string.share_image_failed)) }
        }
    }

    private fun setButtons(enabled: Boolean) {
        listOf(binding.btnSave, binding.btnShare).forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.5f }
    }

    private fun fileName() = "RunTrail_${run.startTime}_${format.name.lowercase()}.jpg"

    /** Saves to the gallery; only Android 9 needs the storage permission. */
    private fun save() {
        val bmp = image ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        setButtons(false)
        val name = fileName()
        runAsync({
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RunTrail")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("cannot create image")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: error("cannot write image")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        }) { result ->
            setButtons(true)
            toast(getString(if (result.isSuccess) R.string.share_image_saved else R.string.share_image_failed))
        }
    }

    /** Shares the image through FileProvider. */
    private fun share() {
        val bmp = image ?: return
        setButtons(false)
        runAsync({
            val dir = File(cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }   // keep only the latest file
            val file = File(dir, fileName())
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            FileProvider.getUriForFile(this, "$packageName.files", file)
        }) { result ->
            setButtons(true)
            result.onSuccess { uri ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(null, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    RunRepository.getById(run.id, withTrack = false)?.shareUrl?.let { putExtra(Intent.EXTRA_TEXT, it) }
                }
                startActivity(Intent.createChooser(send, getString(R.string.share_image_share)))
            }.onFailure { toast(getString(R.string.share_image_failed)) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PHOTO, photoPath)
        outState.putBoolean(KEY_USE_PHOTO, usePhoto)
        outState.putString(KEY_FORMAT, format.name)
    }

    companion object {
        private const val KEY_PHOTO = "photo"
        private const val KEY_USE_PHOTO = "use_photo"
        private const val KEY_FORMAT = "format"
    }
}

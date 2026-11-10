package com.example.runtrail.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * เก็บรูปของการวิ่งลงพื้นที่ส่วนตัวของแอป (MA10 Image)
 * - แก้ทิศตาม EXIF → ย่อด้านยาวไม่เกิน 1280 px → บีบอัด JPEG 80%
 */
object ImageStore {

    private const val MAX_SIDE = 1280
    private const val QUALITY = 80

    fun save(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "run_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
        file.outputStream().use { scale(bitmap).compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        return file.absolutePath
    }

    fun saveFromUri(context: Context, uri: Uri): String? {
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val degrees = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val rotated = if (degrees == 0f) bmp else
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        return save(context, rotated)
    }

    /** รูปที่ดาวน์โหลดจาก server (การวิ่งที่บันทึกจากเครื่องอื่น) */
    fun saveBytes(context: Context, bytes: ByteArray): String? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return save(context, bmp)
    }

    fun load(path: String?): Bitmap? = path?.let { if (File(it).exists()) BitmapFactory.decodeFile(it) else null }

    private fun scale(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= MAX_SIDE) return src
        val ratio = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    }
}

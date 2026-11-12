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
 * - อ่านขนาดก่อน แล้ว decode แบบย่อ (inSampleSize) → ไม่โหลดรูป 50 MP เต็มขนาดเข้าหน่วยความจำ (AUDIT A3)
 * - แก้ทิศตาม EXIF → ย่อด้านยาวไม่เกิน 1280 px → บีบอัด JPEG 80%
 * - งานนี้ช้า (หลายร้อย ms) ต้องเรียกจาก background thread
 */
object ImageStore {

    const val MAX_SIDE = 1280
    private const val QUALITY = 80

    /**
     * ค่า inSampleSize (ยกกำลังของ 2) ที่ทำให้ด้านยาวยังไม่ต่ำกว่า [target]
     * เช่น 8000 px → 4 (ได้ 2000 px แล้วค่อยย่อละเอียดให้เหลือ 1280)
     */
    fun sampleSize(width: Int, height: Int, target: Int = MAX_SIDE): Int {
        var sample = 1
        val longest = max(width, height)
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }

    fun save(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "run_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
        val scaled = scale(bitmap)
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (scaled !== bitmap) scaled.recycle()
        return file.absolutePath
    }

    fun saveFromUri(context: Context, uri: Uri): String? = try {
        decodeScaled(context, uri)?.let { save(context, it) }
    } catch (e: Exception) {
        android.util.Log.w("ImageStore", "cannot import $uri", e)
        null        // ไฟล์เสีย / ไม่ใช่รูป / ถูกลบระหว่างอ่าน
    } catch (e: OutOfMemoryError) {
        android.util.Log.w("ImageStore", "out of memory importing $uri", e)
        null
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // โหมดอ่านขนาดอย่างเดียว decodeStream คืน null เสมอ → ตรวจผลจาก outWidth/outHeight แทน
        val opened = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true }
        if (opened != true) return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
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
                .also { if (it !== bmp) bmp.recycle() }
        return rotated
    }

    /** รูปที่ดาวน์โหลดจาก server (การวิ่งที่บันทึกจากเครื่องอื่น) */
    fun saveBytes(context: Context, bytes: ByteArray): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { save(context, it) }
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    fun load(path: String?): Bitmap? = path?.let { if (File(it).exists()) BitmapFactory.decodeFile(it) else null }

    /** ลบไฟล์รูปที่แอปเก็บไว้เอง (ไม่แตะไฟล์นอกโฟลเดอร์ photos) */
    fun delete(context: Context, path: String?) {
        if (path == null) return
        val f = File(path)
        if (f.parentFile?.canonicalPath == File(context.filesDir, "photos").canonicalPath) f.delete()
    }

    private fun scale(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= MAX_SIDE) return src
        val ratio = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    }
}

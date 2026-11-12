package com.example.runtrail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.ui.common.ImageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** นำเข้ารูปใหญ่จากกล้อง: ย่อแบบไม่โหลดเต็มขนาด + หมุนตาม EXIF (AUDIT A3) */
@RunWith(AndroidJUnit4::class)
class ImageStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun largeRotatedPhotoIsScaledAndRotated() {
        val src = File(ctx.cacheDir, "big_test.jpg")
        Bitmap.createBitmap(4000, 3000, Bitmap.Config.RGB_565).apply { eraseColor(Color.GREEN) }.let { bmp ->
            src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            bmp.recycle()
        }
        ExifInterface(src.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val path = ImageStore.saveFromUri(ctx, Uri.fromFile(src))
        src.delete()
        assertNotNull(path)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        assertEquals(960, opts.outWidth)    // หมุน 90° แล้ว: กว้าง < สูง
        assertEquals(1280, opts.outHeight)
        ImageStore.delete(ctx, path)
        assertFalse(File(path!!).exists())
    }

    @Test fun notAnImageReturnsNull() {
        val src = File(ctx.cacheDir, "not_image.jpg").apply { writeText("hello") }
        assertNull(ImageStore.saveFromUri(ctx, Uri.fromFile(src)))
        src.delete()
    }

    @Test fun deleteNeverTouchesFilesOutsidePhotos() {
        val outside = File(ctx.cacheDir, "keep_me.jpg").apply { writeText("x") }
        ImageStore.delete(ctx, outside.absolutePath)
        assertEquals(true, outside.exists())
        outside.delete()
    }
}

package dev.nulifyer.sharetolens

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePreprocessorTest {
    @Test
    fun prepareAppliesOrientationAndRemovesExifMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("metadata-source-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(77, 91, 184))
            }
            FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()

            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_MAKE, "Private camera")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:14 12:34:56")
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString(),
                )
                setLatLong(40.7128, -74.0060)
                saveAttributes()
            }

            ImagePreprocessor.prepare(
                context.contentResolver,
                Uri.fromFile(source),
                context.cacheDir,
            ) { false }.use { prepared ->
                assertEquals(20, prepared.width)
                assertEquals(40, prepared.height)

                val outputExif = ExifInterface(prepared.file)
                assertNull(outputExif.getAttribute(ExifInterface.TAG_MAKE))
                assertNull(outputExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertNull(outputExif.latLong)
                assertEquals(
                    ExifInterface.ORIENTATION_UNDEFINED,
                    outputExif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED,
                    ),
                )
            }
        } finally {
            source.delete()
        }
    }
}

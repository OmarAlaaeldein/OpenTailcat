package com.tailcat.vpn

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tailcat.vpn.core.qr.QrImageImporter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrImageImportInstrumentedTest {
    @Test fun imageImportDecodesInMemoryWithoutCameraPermission() {
        val matrix = QRCodeWriter().encode("synthetic-imported-qr", BarcodeFormat.QR_CODE, 256, 256)
        val pixels = IntArray(256 * 256) { if (matrix[it % 256, it / 256]) 0xff000000.toInt() else -1 }
        val bitmap = Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        assertEquals("synthetic-imported-qr", QrImageImporter.decode(ByteArrayInputStream(bytes)))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A synthetic image fixture exercises the same content-URI read path
        // as the picker. No real credential image is written to storage.
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val fixture = File.createTempFile("synthetic-qr-", ".png", directory)
        try {
            fixture.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fixture)
            assertEquals("synthetic-imported-qr", runBlocking { QrImageImporter.read(context, uri) })
        } finally {
            fixture.delete()
        }
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.CAMERA))
        assertFalse(permissions.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    @Test fun malformedImageErrorsDoNotExposeContents() {
        val input = "synthetic-private-image-content"
        val error = assertThrows(IllegalArgumentException::class.java) {
            QrImageImporter.decode(ByteArrayInputStream(input.toByteArray()))
        }
        assertFalse(error.message.orEmpty().contains(input))
    }

    @Test fun oversizedInputIsRejectedBeforeImageDecoding() {
        val stream = object : InputStream() {
            private var remaining = 16 * 1024 * 1024 + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length, remaining)
                buffer.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        assertThrows(IllegalArgumentException::class.java) { QrImageImporter.decode(stream) }
    }
}

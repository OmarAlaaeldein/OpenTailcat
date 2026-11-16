package com.tailcat.vpn.core.qr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses the picker URI grant, no camera/storage permission or persistent copy. */
object QrImageImporter {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_DIMENSION = 2048

    suspend fun read(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Choose an image using the image picker." }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open the QR image.")
        stream.use(::decode)
    }

    internal fun decode(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "QR image is too large (maximum 16 MB)." }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This file is not a supported image." }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Could not decode the QR image.")
        try {
            require(bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) {
                "QR image could not be resized safely."
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return QrCodeDecoder.decode(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}

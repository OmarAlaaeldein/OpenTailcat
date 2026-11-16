package com.tailcat.vpn.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class QrCodeDecoderTest {
    private fun image(text: String, inverted: Boolean = false): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 256, 256)
        return IntArray(256 * 256) { i ->
            if (matrix[i % 256, i / 256] != inverted) 0xff000000.toInt() else -1
        }
    }

    @Test fun normalAndInvertedImagesPreserveContents() {
        for (inverted in listOf(false, true)) {
            assertEquals("synthetic-qr-image", QrCodeDecoder.decode(image("synthetic-qr-image", inverted), 256, 256))
        }
    }

    @Test fun rotatedImageDecodes() {
        val original = image("synthetic-rotated-image")
        val rotated = IntArray(original.size) { i -> original[(255 - i % 256) * 256 + i / 256] }
        assertEquals("synthetic-rotated-image", QrCodeDecoder.decode(rotated, 256, 256))
    }

    @Test fun ambiguousImageIsRejected() {
        val left = image("synthetic-first-gateway")
        val right = image("synthetic-second-gateway")
        val pixels = IntArray(576 * 288) { -1 }
        for (y in 0 until 256) for (x in 0 until 256) {
            pixels[(y + 16) * 576 + x + 16] = left[y * 256 + x]
            pixels[(y + 16) * 576 + x + 304] = right[y * 256 + x]
        }
        val error = assertThrows(IllegalArgumentException::class.java) { QrCodeDecoder.decode(pixels, 576, 288) }
        assertTrue(error.message.orEmpty().contains("exactly one"))
        assertFalse(error.message.orEmpty().contains("synthetic"))
    }

    @Test fun blankAndInvalidImagesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { QrCodeDecoder.decode(IntArray(256 * 256) { -1 }, 256, 256) }
        assertThrows(IllegalArgumentException::class.java) { QrCodeDecoder.decode(IntArray(1), Int.MAX_VALUE, Int.MAX_VALUE) }
    }
}

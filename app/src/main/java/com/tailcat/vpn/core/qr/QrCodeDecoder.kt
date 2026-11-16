package com.tailcat.vpn.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/** Decodes one credential in memory; never includes decoded data in an error. */
object QrCodeDecoder {
    fun decode(pixels: IntArray, width: Int, height: Int): String {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) {
            "Invalid QR image dimensions"
        }
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val reader = QRCodeMultiReader()
        val results = try {
            reader.decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints)
        } catch (_: NotFoundException) {
            try {
                reader.decodeMultiple(BinaryBitmap(HybridBinarizer(source.invert())), hints)
            } catch (_: NotFoundException) {
                throw IllegalArgumentException("No QR code found. Choose a clear QR image.")
            }
        }
        require(results.size == 1) { "Choose an image containing exactly one QR code." }
        return results.single().text
    }
}

package com.tailcat.vpn.ui.screens.home

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenQrTest {
    @Test
    fun tokenQrRoundTripsWithoutChangingItsContents() {
        val token = "tco2FwWCAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU-0L38CyuG0cfGFrWCBYaa_0UFSXMsuq7V5d-bMKbaMcsOV0K61a1KGnaPGme2FpGQEu"
        val matrix = encodeTokenQr(token)
        val scale = 4
        val width = matrix.width * scale
        val pixels = IntArray(width * width) { index ->
            if (matrix[(index % width) / scale, (index / width) / scale]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))
        assertEquals(token, QRCodeReader().decode(bitmap).text)
    }
}

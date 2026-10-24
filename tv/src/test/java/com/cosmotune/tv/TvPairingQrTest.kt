package com.cosmotune.tv

import com.cosmotune.music.ui.tv.auth.QrCodeGenerator
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class TvPairingQrTest {
    @Test fun pairingLinksLongerThanOneCorrectionBlockAndUtf8AreScannable() {
        for (text in listOf("http://127.0.0.1:41999/#" + "A1_-".repeat(11),
            "https://example.invalid/pair?code=" + "abc123".repeat(20), "தமிழ் 日本語 हिन्दी")) {
            val matrix = QrCodeGenerator.encode(text)
            val side = (matrix.size + 8) * 4
            val pixels = IntArray(side * side) { index ->
                val y = index / side / 4 - 4; val x = index % side / 4 - 4
                if (y in matrix.indices && x in matrix[y].indices && matrix[y][x]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
            assertEquals(text, QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text)
        }
    }
}

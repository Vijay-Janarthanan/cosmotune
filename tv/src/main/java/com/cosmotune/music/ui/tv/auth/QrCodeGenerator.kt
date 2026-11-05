/*
 * Source: FORK.md companion source record TV-0009
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Standard QR encoding, shared with the already bundled phone/desktop codec. */
object QrCodeGenerator {
    fun encode(text: String): Array<BooleanArray> {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 2_048) { "QR content is too long" }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        ))
        return Array(matrix.height) { row -> BooleanArray(matrix.width) { column -> matrix[column, row] } }
    }
}

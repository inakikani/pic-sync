package com.inakikani.picsync.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code as a square grid of modules, including the 4-module quiet zone. */
class QrModules(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/** Encodes [text] (e.g. the Mac's upload page URL) as a QR code, error correction M. */
fun qrModules(text: String): QrModules {
    // Width/height 0: one matrix cell per module; drawing scales it to the screen.
    val m = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val size = m.width
    return QrModules(size, BooleanArray(size * size) { m[it % size, it / size] })
}

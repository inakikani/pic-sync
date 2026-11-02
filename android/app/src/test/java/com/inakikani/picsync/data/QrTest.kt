package com.inakikani.picsync.data

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QrTest {
    /** Renders the modules at [scale] px each and decodes them with ZXing's reader. */
    private fun decode(m: QrModules, scale: Int = 4): String {
        val w = m.size * scale
        val px = IntArray(w * w) { i ->
            val x = (i % w) / scale
            val y = (i / w) / scale
            if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, px)))).text
    }

    @Test fun roundTripsTheServerUrl() {
        val url = "http://192.168.1.23:8080"
        assertEquals(url, decode(qrModules(url)))
    }

    @Test fun hasAQuietZone() {
        val m = qrModules("http://10.0.0.2:8080")
        for (i in 0 until m.size) {
            for (edge in 0 until 4) {
                assertFalse(m[i, edge])
                assertFalse(m[edge, i])
                assertFalse(m[i, m.size - 1 - edge])
                assertFalse(m[m.size - 1 - edge, i])
            }
        }
    }
}

package com.inakikani.picsync.ui

import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.inakikani.picsync.data.AppLog
import com.inakikani.picsync.data.qrModules
import kotlin.math.floor

/**
 * Shows the Mac's upload page as a QR code, so another phone (e.g. an iPhone) on
 * the same Wi-Fi can scan it with its camera and upload from its browser. The
 * screen goes to full brightness while it's shown, which helps cameras read it.
 */
@Composable
fun ServerQrDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            AppLog.d("settings: showing the upload page QR code for $url")
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
            onDispose { }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Upload from another device", style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
                QrCode(url, Modifier.widthIn(max = 280.dp).fillMaxWidth())
                SelectionContainer { Text(url, style = MaterialTheme.typography.titleMedium) }
                Text(
                    "Scan with the camera of another phone or tablet on the same Wi-Fi. It opens pic-sync's upload page in the browser.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onDismiss, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

/**
 * A QR code drawn as crisp squares (always dark on white, whatever the theme, so
 * every camera can read it). Modules are snapped to whole pixels: fractional
 * sizes leave hairline gaps that some scanners trip over.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White),
    ) {
        val cell = floor(size.minDimension / modules.size)
        val origin = (size.minDimension - cell * modules.size) / 2
        for (y in 0 until modules.size) {
            for (x in 0 until modules.size) {
                if (modules[x, y]) {
                    drawRect(Color.Black, topLeft = Offset(origin + x * cell, origin + y * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

package com.inakikani.picsync.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.inakikani.picsync.data.AppLog
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun ConnectScreen(vm: AppViewModel, state: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var manual by rememberSaveable { mutableStateOf("") }
    var networkDenied by remember { mutableStateOf(false) }
    val gate = rememberLocalNetworkGate { networkDenied = true }

    fun connect(url: String) {
        networkDenied = false
        gate { vm.connect(url) }
    }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect to your Mac", style = MaterialTheme.typography.headlineSmall)
            Text("Start pic-sync on your Mac, then scan the QR code it shows. Both devices must be on the same Wi-Fi.")

            Button(
                onClick = { scanQr(ctx, onResult = ::connect, onError = vm::setConnectError) },
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan QR code") }

            Text("or enter the address", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text("e.g. 192.168.1.23:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (manual.isNotBlank()) connect(manual) }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { connect(manual) },
                enabled = manual.isNotBlank() && !state.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }

            if (state.connecting) CircularProgressIndicator()
            state.connectError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { shareDiagnosticsAsync(ctx, scope, state) }) { Text("Share diagnostics") }
            }
            if (networkDenied) {
                Text(
                    "pic-sync needs permission to reach devices on your local network (\"Nearby devices\") " +
                        "to talk to your Mac.",
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { openAppSettings(ctx) }) { Text("Open app settings") }
            }
        }
    }
}

/** Google's code scanner: runs in Play services, so the app needs no camera permission. */
private fun scanQr(ctx: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(ctx, options).startScan()
        .addOnSuccessListener { barcode ->
            val text = barcode.rawValue
            if (text.isNullOrBlank()) onError("That QR code has no address in it") else onResult(text)
        }
        .addOnFailureListener { e ->
            AppLog.w("QR scanner failed", e)
            onError("Couldn't start the QR scanner (${e.message}). Enter the address instead.")
        }
}

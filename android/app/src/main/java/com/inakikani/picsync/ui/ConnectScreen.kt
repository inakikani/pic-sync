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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.inakikani.picsync.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.inakikani.picsync.data.AppLog
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectScreen(vm: AppViewModel, state: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var manual by rememberSaveable { mutableStateOf("") }
    var networkDenied by remember { mutableStateOf(false) }
    val gate = rememberLocalNetworkGate { networkDenied = true }
    LaunchedEffect(Unit) { AppLog.i("startup: connect screen shown") }

    fun connect(url: String) {
        networkDenied = false
        gate { vm.connect(url) }
    }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Expressive hero: the QR symbol in one of Material's "cookie" shapes.
            Box(
                Modifier.padding(top = 24.dp).size(112.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text("Connect to your Mac", style = MaterialTheme.typography.displaySmallEmphasized)
            Text(
                "Start pic-sync on your Mac, then scan the QR code it shows. Both devices must be on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { scanQr(ctx, onResult = ::connect, onError = vm::setConnectError) },
                shapes = ButtonDefaults.shapes(),
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Scan QR code", style = MaterialTheme.typography.titleMedium)
            }

            Text("or enter the address", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                shapes = ButtonDefaults.shapes(),
                enabled = manual.isNotBlank() && !state.connecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Connect") }

            if (state.connecting) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.connectError?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { shareDiagnosticsAsync(ctx, scope, state) }, shapes = ButtonDefaults.shapes()) {
                            Text("Share diagnostics")
                        }
                    }
                }
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

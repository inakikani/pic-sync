package com.inakikani.picsync.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inakikani.picsync.MainActivity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareScreen(vm: ShareViewModel, url: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val fraction by vm.fraction.collectAsStateWithLifecycle()

    // Start as soon as the items are known (asking for local network access first if needed).
    val gate = rememberLocalNetworkGate {
        vm.failToStart("pic-sync needs the Nearby devices (local network) permission to reach your Mac")
    }
    LaunchedEffect(state.loaded, url) {
        if (state.loaded && state.rows.isNotEmpty()) gate { vm.start(url) }
    }

    // Shared files can only be read while this screen is open: keep it on.
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("Send to Mac") },
                subtitle = { Text(url) },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val total = state.rows.size
                    when {
                        state.running -> {
                            Text("Sending ${state.current + 1} of $total", style = MaterialTheme.typography.titleSmallEmphasized)
                            Text("Keep this screen open until it's done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearWavyProgressIndicator(
                                progress = { if (total == 0) 0f else (state.current.coerceAtLeast(0) + fraction) / total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(onClick = vm::cancel, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                        }
                        state.finished -> {
                            state.summary?.let { Text(it, style = MaterialTheme.typography.titleSmallEmphasized) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    shapes = ButtonDefaults.shapes(),
                                    onClick = {
                                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        onDone()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Open pic-sync") }
                                Button(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("Done") }
                            }
                        }
                        else -> Text("Preparing…")
                    }
                }
            }
        },
    ) { padding ->
        when {
            !state.loaded -> Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { LoadingIndicator(Modifier.size(64.dp)) }
            state.rows.isEmpty() -> Column(Modifier.padding(padding).padding(24.dp)) {
                Text("Nothing to send: no files were shared with pic-sync.")
                TextButton(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text("Close") }
            }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.rows, key = { _, row -> row.item.uri.toString() }) { i, row ->
                    ShareRowView(row, progress = if (i == state.current) fraction else null)
                }
            }
        }
    }
}

@Composable
private fun ShareRowView(row: ShareRow, progress: Float?) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            row.item.size?.let {
                Spacer(Modifier.size(8.dp))
                Text(
                    "%.1f MB".format(Locale.getDefault(), it / 1e6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val ok = Color(0xFF16A34A)
        val (text, color) = when (row.status) {
            ShareStatus.WAITING -> "Waiting" to MaterialTheme.colorScheme.onSurfaceVariant
            ShareStatus.SENDING -> "Sending…" to MaterialTheme.colorScheme.primary
            ShareStatus.SAVED, ShareStatus.DUPLICATE -> "✓ ${row.detail}" to ok
            ShareStatus.FAILED -> "✗ ${row.detail}" to MaterialTheme.colorScheme.error
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        when (row.onPhone) {
            true -> Text(
                "On this phone: can be moved to trash from pic-sync",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            false -> Text(
                "Not found on this phone (cloud-only or a different version): nothing to trash here",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            null -> {}
        }
        if (progress != null) {
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

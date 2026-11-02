package com.inakikani.picsync.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import com.inakikani.picsync.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun appVersion(ctx: Context): String = runCatching {
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
}.getOrDefault("?")

/** One line with everything that decides what the app may do. Logged at start and in diagnostics. */
fun environmentLine(ctx: Context): String =
    "app ${appVersion(ctx)}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
        "${Build.MANUFACTURER} ${Build.MODEL}; permissions: photos=${mediaAccess(ctx)}, " +
        "location=${if (hasMediaLocation(ctx)) "yes" else "no"}, " +
        "local network=${if (hasLocalNetworkAccess(ctx)) "yes" else "no"}, " +
        "media management=${if (MediaStore.canManageMedia(ctx)) "yes" else "no"}"

/** Text for Settings → Share diagnostics: environment, current state and the recent log. Blocking. */
fun buildDiagnostics(ctx: Context, state: UiState): String = buildString {
    appendLine("pic-sync diagnostics, ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
    appendLine(environmentLine(ctx))
    appendLine("server: ${state.serverUrl ?: "(not connected)"}")
    appendLine(
        "library: ${state.media.size} items, ${state.records.size} verified uploads, " +
            "${state.excluded.size} skipped, ${state.failed.size} failed this session",
    )
    if (state.failed.isNotEmpty()) {
        appendLine("failed this session:")
        state.failed.values.forEach { appendLine("  $it") }
    }
    appendLine()
    appendLine("---- log ----")
    append(AppLog.recent())
}

/** Builds the diagnostics off the main thread (it reads the log file), then opens the share sheet. */
fun shareDiagnosticsAsync(ctx: Context, scope: CoroutineScope, state: UiState) {
    scope.launch {
        val text = withContext(Dispatchers.IO) { buildDiagnostics(ctx, state) }
        AppLog.i("diagnostics: sharing ${text.length} characters")
        shareDiagnostics(ctx, text)
    }
}

fun shareDiagnostics(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "pic-sync diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Share pic-sync diagnostics"))
}

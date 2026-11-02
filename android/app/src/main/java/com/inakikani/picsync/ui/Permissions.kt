package com.inakikani.picsync.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// ---- local network (Android 17+, targetSdk 37) ----

/** String form so this compiles regardless of the SDK constant's availability. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

fun needsLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT >= 37

fun hasLocalNetworkAccess(ctx: Context): Boolean =
    !needsLocalNetworkPermission() ||
        ctx.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

/**
 * Returns a function that runs an action once local network access is available,
 * asking for the permission first if needed. [onDenied] runs if the user refuses.
 */
@Composable
fun rememberLocalNetworkGate(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else onDenied()
    }
    return { action ->
        if (hasLocalNetworkAccess(ctx)) {
            action()
        } else {
            pending = action
            launcher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
}

// ---- photos and videos ----

enum class MediaAccess { FULL, PARTIAL, NONE }

fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Including USER_SELECTED lets Android 14+ offer "Allow limited access".
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
    } else {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
    }

/** Whether photos can be read with their GPS location intact (see MediaRepository.open). */
fun hasMediaLocation(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

fun mediaAccess(ctx: Context): MediaAccess {
    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
    val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
    return when {
        images && videos -> MediaAccess.FULL
        images || videos -> MediaAccess.PARTIAL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
        else -> MediaAccess.NONE
    }
}

// ---- settings screens ----

fun openAppSettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** The "Media management" special-access page for this app. */
fun openManageMediaSettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

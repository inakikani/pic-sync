package com.inakikani.picsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inakikani.picsync.data.AppLog
import com.inakikani.picsync.ui.AppViewModel
import com.inakikani.picsync.ui.ConnectScreen
import com.inakikani.picsync.ui.PicSyncTheme
import com.inakikani.picsync.ui.ShareScreen
import com.inakikani.picsync.ui.ShareViewModel

/**
 * "pic-sync" in the Android share sheet (e.g. from Google Photos): uploads the
 * shared photos/videos to the Mac. Shared files can only be read while this
 * screen is open, so it stays open (screen on) until the upload is done.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uris = sharedUris(intent)
        if (savedInstanceState == null) {
            AppLog.i("share: received ${uris.size} item(s) (${intent.type}) from ${referrer?.host ?: "unknown app"}")
        }
        setContent { PicSyncTheme { ShareContent(uris, onDone = ::finish) } }
    }
}

/** The shared URIs, from ClipData (which carries the read grants) and EXTRA_STREAM. */
private fun sharedUris(intent: Intent): List<Uri> {
    val out = LinkedHashSet<Uri>()
    intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add) }
    when (intent.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(out::add)
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(out::addAll)
    }
    return out.toList()
}

@Composable
private fun ShareContent(uris: List<Uri>, onDone: () -> Unit) {
    // Same connection as the main app; if there's none yet, connect first.
    val appVm: AppViewModel = viewModel()
    val appState by appVm.state.collectAsStateWithLifecycle()
    val vm: ShareViewModel = viewModel()
    LaunchedEffect(Unit) { vm.load(uris) }
    val url = appState.serverUrl
    if (url == null) ConnectScreen(appVm, appState) else ShareScreen(vm, url, onDone)
}

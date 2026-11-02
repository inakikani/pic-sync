package com.inakikani.picsync

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
import com.inakikani.picsync.ui.GalleryScreen
import com.inakikani.picsync.ui.PicSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("startup: activity created")
        enableEdgeToEdge()
        setContent { PicSyncTheme { PicSyncApp() } }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("startup: activity resumed")
    }
}

@Composable
private fun PicSyncApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val connected = state.serverUrl != null
    LaunchedEffect(connected) {
        AppLog.i("startup: showing ${if (connected) "gallery (server ${state.serverUrl})" else "connect screen"}")
    }
    if (!connected) ConnectScreen(vm, state) else GalleryScreen(vm, state)
}

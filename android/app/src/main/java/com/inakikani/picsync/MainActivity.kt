package com.inakikani.picsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inakikani.picsync.ui.AppViewModel
import com.inakikani.picsync.ui.ConnectScreen
import com.inakikani.picsync.ui.GalleryScreen
import com.inakikani.picsync.ui.PicSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PicSyncTheme { PicSyncApp() } }
    }
}

@Composable
private fun PicSyncApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.serverUrl == null) ConnectScreen(vm, state) else GalleryScreen(vm, state)
}

package com.inakikani.picsync.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.inakikani.picsync.data.Album
import com.inakikani.picsync.data.MediaFilters
import com.inakikani.picsync.data.MediaItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: AppViewModel, state: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Media permission
    var access by remember { mutableStateOf(mediaAccess(ctx)) }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = mediaAccess(ctx)
        vm.refreshMedia()
    }
    LaunchedEffect(Unit) { if (access == MediaAccess.NONE) mediaLauncher.launch(mediaPermissions()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = mediaAccess(ctx)
        if (access != MediaAccess.NONE) vm.refreshMedia()
    }

    // Messages
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            vm.dismissMessage()
        }
    }

    // Uploads only run while the app is open, so keep the screen on meanwhile.
    val view = LocalView.current
    val uploading = state.upload != null
    DisposableEffect(uploading) {
        view.keepScreenOn = uploading
        onDispose { view.keepScreenOn = false }
    }

    val networkGate = rememberLocalNetworkGate {
        vm.showMessage("pic-sync needs the Nearby devices (local network) permission to reach your Mac")
    }

    // Trash flow: system confirmation, or our own dialog when Media management skips it.
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onTrashResult(it.resultCode == Activity.RESULT_OK)
    }
    var confirmTrash by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    fun runTrash() {
        scope.launch {
            vm.prepareTrash()?.let { trashLauncher.launch(IntentSenderRequest.Builder(it).build()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("pic-sync")
                        Text(state.serverUrl.orEmpty(), style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { TextButton(onClick = { showSettings = true }) { Text("Settings") } },
            )
        },
        bottomBar = {
            ActionBar(
                state = state,
                onSelectNotUploaded = vm::selectNotUploaded,
                onClear = vm::clearSelection,
                onUpload = { networkGate { vm.uploadSelected() } },
                onCancel = vm::cancelUpload,
                onTrash = { if (vm.canManageMedia()) { confirmTrash = true } else { runTrash() } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (access == MediaAccess.NONE) {
                NoAccess(
                    onGrant = { mediaLauncher.launch(mediaPermissions()) },
                    onSettings = { openAppSettings(ctx) },
                )
            } else {
                MediaGrid(vm, state, access, onChangeAccess = { mediaLauncher.launch(mediaPermissions()) })
            }
        }
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move to trash?") },
            text = {
                Text(
                    "Move ${state.uploadedOnPhone} uploaded item(s)${state.scopeLabel} to the phone's trash? " +
                        "They stay recoverable there for 30 days.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmTrash = false; runTrash() }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } },
        )
    }

    if (showSettings) {
        val canManage = vm.canManageMedia()
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Server", style = MaterialTheme.typography.titleSmall)
                    Text(state.serverUrl.orEmpty())
                    Spacer(Modifier.size(8.dp))
                    Text("Trash confirmation", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (canManage) {
                            "Media management is allowed, so Android won't ask for confirmation. " +
                                "pic-sync asks you itself before moving anything to trash."
                        } else {
                            "Android asks you to confirm each batch. To skip that, allow " +
                                "\"Media management\" for pic-sync."
                        },
                    )
                    TextButton(onClick = { openManageMediaSettings(ctx) }) { Text("Open Media management") }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } },
            dismissButton = {
                TextButton(onClick = { showSettings = false; vm.disconnect() }) { Text("Change server") }
            },
        )
    }

    state.busy?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(msg)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaGrid(vm: AppViewModel, state: UiState, access: MediaAccess, onChangeAccess: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (access == MediaAccess.PARTIAL) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Limited access: only photos you allowed are shown",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onChangeAccess) { Text("Change") }
            }
        }

        if (state.albums.isNotEmpty()) {
            AlbumRow(state.albums, state.selectedAlbum, state.media.size, onSelect = vm::selectAlbum)
        }

        var showFilters by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !state.onlyNotUploaded,
                onClick = { vm.setOnlyNotUploaded(false) },
                label = { Text("All (${state.inScope.size})") },
            )
            FilterChip(
                selected = state.onlyNotUploaded,
                onClick = { vm.setOnlyNotUploaded(true) },
                label = { Text("Not uploaded (${state.notUploaded.size})") },
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = state.filters.isActive,
                onClick = { showFilters = true },
                label = {
                    Text(if (state.filters.isActive) "Filters (${state.filters.activeCount})" else "Filters")
                },
            )
        }
        if (state.filters.isActive) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.filters.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.setFilters(MediaFilters()) }) { Text("Clear") }
            }
        }
        if (showFilters) {
            FiltersSheet(state.filters, onChange = vm::setFilters, onDismiss = { showFilters = false })
        }

        val visible = if (state.onlyNotUploaded) state.notUploaded else state.inScope
        when {
            !state.mediaLoaded -> Centered { CircularProgressIndicator() }
            visible.isEmpty() -> Centered {
                Text(
                    when {
                        state.filters.isActive && state.inScope.isEmpty() -> "Nothing matches the filters"
                        state.onlyNotUploaded -> "Everything ${state.albumName?.let { "in $it " } ?: ""}is uploaded"
                        else -> "No photos or videos found"
                    },
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    val up = state.upload
                    MediaCell(
                        item = item,
                        loadThumbnail = vm::thumbnail,
                        uploaded = item.id in state.records,
                        failed = item.id in state.failed,
                        selected = item.id in state.selected,
                        excluded = item.id in state.excluded,
                        progress = up?.takeIf { it.currentId == item.id }?.fraction,
                        onClick = { vm.toggle(item.id) },
                        onLongClick = { vm.toggleExcluded(item.id) },
                    )
                }
            }
        }
    }
}

/** Horizontal album picker. Each chip shows the album size and a badge with items left to upload. */
@Composable
private fun AlbumRow(albums: List<Album>, selected: Long?, total: Int, onSelect: (Long?) -> Unit) {
    val pendingTotal = albums.sumOf { it.notUploaded }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            AlbumChip("All photos", total, pendingTotal, selected == null) { onSelect(null) }
        }
        rowItems(albums, key = { it.id }) { album ->
            AlbumChip(album.name, album.count, album.notUploaded, selected == album.id) { onSelect(album.id) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumChip(name: String, count: Int, pending: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$name ($count)") },
        trailingIcon = if (pending > 0) {
            {
                Box(
                    Modifier.background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp),
                ) {
                    Text(
                        pending.toString(),
                        color = MaterialTheme.colorScheme.onTertiary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ActionBar(
    state: UiState,
    onSelectNotUploaded: () -> Unit,
    onClear: () -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit,
    onTrash: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val up = state.upload
            if (up != null) {
                Text("Uploading ${up.index + 1} of ${up.total}")
                LinearProgressIndicator(
                    progress = { (up.index + up.fraction) / up.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            } else {
                IdleActions(state, onSelectNotUploaded, onClear, onUpload, onTrash)
            }
        }
    }
}

@Composable
private fun IdleActions(
    state: UiState,
    onSelectNotUploaded: () -> Unit,
    onClear: () -> Unit,
    onUpload: () -> Unit,
    onTrash: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.selected.isEmpty()) {
                OutlinedButton(
                    onClick = onSelectNotUploaded,
                    enabled = state.notUploaded.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Select not uploaded") }
            } else {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Text("Clear (${state.selected.size})")
                }
            }
            Button(
                onClick = onUpload,
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(if (state.selected.isEmpty()) "Upload" else "Upload ${state.selected.size}") }
        }
        if (state.uploadedOnPhone > 0) {
            FilledTonalButton(onClick = onTrash, modifier = Modifier.fillMaxWidth()) {
                Text("Move ${state.uploadedOnPhone} uploaded${state.scopeLabel} to trash")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: MediaItem,
    loadThumbnail: suspend (MediaItem) -> ImageBitmap?,
    uploaded: Boolean,
    failed: Boolean,
    selected: Boolean,
    excluded: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(null, item.id) { value = loadThumbnail(item) }
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        thumb?.let {
            Image(
                it,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (excluded) Modifier.alpha(0.4f) else Modifier),
            )
        }
        if (item.isVideo) {
            Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
        when {
            excluded -> Badge("⊘", MaterialTheme.colorScheme.error)
            progress != null -> Badge("${(progress * 100).toInt()}%", MaterialTheme.colorScheme.primary)
            uploaded -> Badge("✓", Color(0xFF16A34A))
            failed -> Badge("!", MaterialTheme.colorScheme.error)
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Badge(text: String, color: Color) {
    Box(
        Modifier.align(Alignment.TopEnd).padding(4.dp).background(color, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) { Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun NoAccess(onGrant: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "pic-sync needs access to your photos and videos to upload them.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onGrant) { Text("Allow access") }
        TextButton(onClick = onSettings) { Text("Open app settings") }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

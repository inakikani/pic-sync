package com.inakikani.picsync.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.inakikani.picsync.data.AppLog
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.derivedStateOf
import com.inakikani.picsync.data.MonthGroup
import com.inakikani.picsync.data.groupByMonthKey
import com.inakikani.picsync.data.monthLabel
import com.inakikani.picsync.data.shortMonthLabel
import java.time.YearMonth
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.inakikani.picsync.R
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
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inakikani.picsync.data.Album
import com.inakikani.picsync.data.MediaFilters
import com.inakikani.picsync.data.MediaItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryScreen(vm: AppViewModel, state: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Media permission
    var access by remember { mutableStateOf(mediaAccess(ctx)) }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        access = mediaAccess(ctx)
        AppLog.i("permissions: asked ${result.keys.map { it.substringAfterLast('.') }} -> $result; photo access now $access")
        vm.refreshMedia()
    }
    fun askMediaPermissions(why: String) {
        AppLog.i("permissions: asking for photos/videos + media location ($why)")
        mediaLauncher.launch(mediaPermissions())
    }
    // Only ask automatically when there's no photo access at all (photos + location
    // together). Installs that already had photo access can allow location in Settings,
    // so a limited-access user isn't shown the photo selection again at every launch.
    LaunchedEffect(Unit) {
        AppLog.i("startup: gallery shown, photo access $access")
        if (access == MediaAccess.NONE) askMediaPermissions("no photo access yet")
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = mediaAccess(ctx)
        AppLog.d("gallery: resumed, photo access $access")
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
    var showFailures by remember { mutableStateOf(false) }
    var showServerQr by remember { mutableStateOf(false) }
    fun runTrash() {
        scope.launch {
            vm.prepareTrash()?.let { trashLauncher.launch(IntentSenderRequest.Builder(it).build()) }
        }
    }

    // Android 16/17 style: a large title with the Mac's address under it, collapsing
    // into a compact bar as the grid scrolls.
    val topBarScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("pic-sync") },
                subtitle = { Text(state.serverUrl.orEmpty()) },
                actions = {
                    IconButton(onClick = { showSettings = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
                scrollBehavior = topBarScroll,
            )
        },
        bottomBar = {
            ActionBar(
                state = state,
                uploadFraction = vm.uploadFraction.collectAsStateWithLifecycle(),
                onSelectNotUploaded = vm::selectNotUploaded,
                onClear = vm::clearSelection,
                onUpload = { networkGate { vm.uploadSelected() } },
                onCancel = vm::cancelUpload,
                onTrash = { if (vm.canManageMedia()) { confirmTrash = true } else { runTrash() } },
                onShowFailures = { showFailures = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (access == MediaAccess.NONE) {
                NoAccess(
                    onGrant = { askMediaPermissions("user tapped Allow") },
                    onSettings = { openAppSettings(ctx) },
                )
            } else {
                MediaGrid(vm, state, access, onChangeAccess = { askMediaPermissions("user changed access") })
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

    if (showServerQr) {
        state.serverUrl?.let { ServerQrDialog(it, onDismiss = { showServerQr = false }) }
    }

    if (showFailures) {
        AlertDialog(
            onDismissRequest = { showFailures = false },
            title = { Text("Failed uploads") },
            text = {
                Column(
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.failed.values.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Select them (tap) and press Upload to retry. More detail: Settings → Share diagnostics.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showFailures = false }) { Text("Close") } },
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
                    // Lets another phone (e.g. an iPhone, without this app) open the web upload page.
                    FilledTonalButton(
                        onClick = { showSettings = false; showServerQr = true },
                        shapes = ButtonDefaults.shapes(),
                        enabled = state.serverUrl != null,
                    ) {
                        Icon(painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Show QR code for the upload page")
                    }
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
                    Spacer(Modifier.size(8.dp))
                    Text("Photo location (GPS)", style = MaterialTheme.typography.titleSmall)
                    if (hasMediaLocation(ctx)) {
                        Text("Allowed: uploads keep their location.")
                    } else {
                        Text(
                            "Not allowed: Android removes the location from photos before upload. " +
                                "If the screen stays empty after tapping Allow, press Back and allow " +
                                "it in the app settings instead.",
                        )
                        Row {
                            TextButton(onClick = { showSettings = false; askMediaPermissions("Settings: allow location") }) {
                                Text("Allow")
                            }
                            TextButton(onClick = { openAppSettings(ctx) }) { Text("App settings") }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("Troubleshooting", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Sends the app's recent log, versions and permissions (file names and the " +
                            "server address included, no photos) to an app of your choice.",
                    )
                    TextButton(onClick = { shareDiagnosticsAsync(ctx, scope, state) }) { Text("Share diagnostics") }
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
                    LoadingIndicator(Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(msg)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
        // All / Not uploaded / Uploaded as a connected button group (Material 3 Expressive),
        // with the filters as a separate toggle next to it.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                val views = listOf(
                    UploadView.ALL to ("All" to state.inScope.size),
                    UploadView.NOT_UPLOADED to ("To upload" to state.notUploaded.size),
                    UploadView.UPLOADED to ("Uploaded" to state.uploaded.size),
                )
                views.forEachIndexed { i, (view, label) ->
                    ToggleButton(
                        checked = state.uploadView == view,
                        onCheckedChange = { vm.setUploadView(view) },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            views.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label.first, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text("${label.second}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
            FilledTonalIconToggleButton(
                checked = state.filters.isActive,
                onCheckedChange = { showFilters = true },
                shapes = IconButtonDefaults.toggleableShapes(),
            ) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Filters")
            }
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

        val visible = when (state.uploadView) {
            UploadView.ALL -> state.inScope
            UploadView.NOT_UPLOADED -> state.notUploaded
            UploadView.UPLOADED -> state.uploaded
        }
        when {
            !state.mediaLoaded -> Centered { LoadingIndicator(Modifier.size(64.dp)) }
            visible.isEmpty() -> Centered {
                Text(
                    when {
                        state.filters.isActive && state.inScope.isEmpty() -> "Nothing matches the filters"
                        state.uploadView == UploadView.NOT_UPLOADED ->
                            "Everything ${state.albumName?.let { "in $it " } ?: ""}is uploaded"
                        state.uploadView == UploadView.UPLOADED ->
                            "Nothing ${state.albumName?.let { "in $it " } ?: ""}is uploaded yet"
                        else -> "No photos or videos found"
                    },
                )
            }
            else -> {
                val fraction = vm.uploadFraction.collectAsStateWithLifecycle()
                var viewing by remember { mutableStateOf<Long?>(null) } // id shown full screen
                // Month sections, like Google Photos: a full-width "November 2026" header
                // above each month's photos (newest first, by date taken).
                val months = remember(visible) { MonthIndex(groupByMonthKey(visible) { it.month }) }
                val gridState = rememberLazyGridState()
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(2.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        for (group in months.groups) {
                            item(
                                key = "month-${group.month}",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "month",
                            ) {
                                val pending = group.items.filter { it.id !in state.records && it.id !in state.excluded }
                                MonthHeader(
                                    group.month,
                                    total = group.items.size,
                                    pending = pending.size,
                                    allSelected = pending.isNotEmpty() && pending.all { it.id in state.selected },
                                    enabled = state.upload == null,
                                    onToggle = { vm.toggleMonth(group.items) },
                                )
                            }
                            items(group.items, key = { it.id }, contentType = { "photo" }) { item ->
                                val up = state.upload
                                MediaCell(
                                    item = item,
                                    loadThumbnail = vm::thumbnail,
                                    cachedThumbnail = vm::cachedThumbnail,
                                    uploaded = item.id in state.records,
                                    failed = item.id in state.failed,
                                    selected = item.id in state.selected,
                                    excluded = item.id in state.excluded,
                                    // A lambda, so only this cell re-renders as the percentage changes.
                                    progress = if (up?.currentId == item.id) ({ fraction.value }) else null,
                                    onClick = { vm.toggle(item.id) },
                                    onLongClick = { vm.toggleExcluded(item.id) },
                                    onOpen = { viewing = item.id },
                                )
                            }
                        }
                    }
                    viewing?.let { id ->
                        MediaViewer(visible, startId = id, state = state, vm = vm, onClose = { viewing = null })
                    }
                    ScrollMonthLabel(gridState, months, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/**
 * "Nov 2026" at the top while scrolling. Its own composable, reading the scroll
 * position only through derivedStateOf: reading it in the grid's scope re-ran the
 * whole grid's setup for every row scrolled.
 */
@Composable
private fun ScrollMonthLabel(gridState: LazyGridState, months: MonthIndex, modifier: Modifier = Modifier) {
    val month by remember(months) {
        derivedStateOf {
            if (gridState.isScrollInProgress && gridState.firstVisibleItemIndex > 0) {
                months.monthAt(gridState.firstVisibleItemIndex)
            } else {
                null
            }
        }
    }
    month?.let {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp,
            modifier = modifier.padding(top = 8.dp),
        ) {
            Text(
                shortMonthLabel(it),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** Month groups plus the grid index where each header sits, to tell which month is on screen. */
private class MonthIndex(val groups: List<MonthGroup<MediaItem>>) {
    private val headerAt = IntArray(groups.size).also { starts ->
        var index = 0
        groups.forEachIndexed { i, g ->
            starts[i] = index
            index += 1 + g.items.size // header + its photos
        }
    }

    /** The month that grid item [index] (header or photo) belongs to. */
    fun monthAt(index: Int): YearMonth? {
        if (groups.isEmpty()) return null
        val pos = headerAt.binarySearch(index)
        val group = if (pos >= 0) pos else (-pos - 2).coerceAtLeast(0)
        return groups[group].month
    }
}

/**
 * "November 2026 · 132 · 20 to upload  [Select 20]". The button selects the month's
 * photos that still need uploading (not uploaded, not skipped), so a whole month
 * can be uploaded with one more tap on Upload; tapped again it deselects them.
 */
@Composable
private fun MonthHeader(
    month: YearMonth,
    total: Int,
    pending: Int,
    allSelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(monthLabel(month), style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                if (pending > 0) "$total · $pending to upload" else "$total · all uploaded",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pending > 0) {
            TextButton(onClick = onToggle, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Text(if (allSelected) "Deselect" else "Select $pending")
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
    uploadFraction: State<Float>,
    onSelectNotUploaded: () -> Unit,
    onClear: () -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit,
    onTrash: () -> Unit,
    onShowFailures: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val up = state.upload
            if (up != null) {
                Text("Uploading ${up.index + 1} of ${up.total}", style = MaterialTheme.typography.titleSmallEmphasized)
                LinearWavyProgressIndicator(
                    progress = { (up.index + uploadFraction.value) / up.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            } else {
                IdleActions(state, onSelectNotUploaded, onClear, onUpload, onTrash, onShowFailures)
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
    onShowFailures: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.failed.isNotEmpty()) {
            TextButton(onClick = onShowFailures, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Text("${state.failed.size} failed to upload · see why", color = MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.selected.isEmpty()) {
                OutlinedButton(
                    onClick = onSelectNotUploaded,
                    shapes = ButtonDefaults.shapes(),
                    enabled = state.notUploaded.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Select not uploaded", maxLines = 1) }
            } else {
                OutlinedButton(onClick = onClear, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Clear (${state.selected.size})")
                }
            }
            Button(
                onClick = onUpload,
                shapes = ButtonDefaults.shapes(),
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_cloud_upload), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(if (state.selected.isEmpty()) "Upload" else "Upload ${state.selected.size}")
            }
        }
        if (state.uploadedOnPhone > 0) {
            FilledTonalButton(onClick = onTrash, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Move ${state.uploadedOnPhone} uploaded${state.scopeLabel} to trash", maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: MediaItem,
    loadThumbnail: suspend (MediaItem) -> ImageBitmap?,
    cachedThumbnail: (Long) -> ImageBitmap?,
    uploaded: Boolean,
    failed: Boolean,
    selected: Boolean,
    excluded: Boolean,
    progress: (() -> Float)?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
) {
    // Cached thumbnails show in the first frame; others load in the background.
    val thumb by produceState(cachedThumbnail(item.id), item.id) { if (value == null) value = loadThumbnail(item) }
    // Selecting shrinks the photo into a rounded tile with a springy motion, like Google
    // Photos on Android 16/17; the selection tint shows around it.
    val motion = MaterialTheme.motionScheme
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, motion.fastSpatialSpec(), label = "inset")
    val corner by animateDpAsState(if (selected) 16.dp else 4.dp, motion.fastSpatialSpec(), label = "corner")
    Box(
        Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // The expressive spring overshoots: animating back to 0 briefly dips below it,
        // and negative padding throws (that crashed the app when unselecting). Clamp.
        Box(Modifier.fillMaxSize().padding(inset.coerceAtLeast(0.dp)).clip(RoundedCornerShape(corner.coerceAtLeast(0.dp)))) {
            thumb?.let {
                Image(
                    it,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (excluded) Modifier.alpha(0.4f) else Modifier),
                )
            }
        }
        if (item.isVideo) {
            Icon(
                painterResource(R.drawable.ic_play_arrow),
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp),
            )
        }
        when {
            excluded -> StatusBadge(R.drawable.ic_block, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Skipped")
            progress != null -> Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("${(progress() * 100).toInt()}%", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
            uploaded -> StatusBadge(R.drawable.ic_check, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "On the Mac")
            failed -> StatusBadge(R.drawable.ic_priority_high, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Upload failed")
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
        // Open full screen. A separate button, since tap selects and long-press skips.
        Box(
            Modifier.align(Alignment.BottomEnd).padding(3.dp).size(30.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClickLabel = "View full screen", onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** A small round status icon in the thumbnail's top-right corner (on the Mac, skipped, failed). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.StatusBadge(icon: Int, container: Color, content: Color, label: String) {
    Box(
        Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = label, tint = content, modifier = Modifier.size(15.dp)) }
}

@Composable
private fun NoAccess(onGrant: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Allow access to your photos", style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Text(
            "pic-sync needs access to your photos and videos to upload them.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGrant, shapes = ButtonDefaults.shapes()) { Text("Allow access") }
        TextButton(onClick = onSettings, shapes = ButtonDefaults.shapes()) { Text("Open app settings") }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

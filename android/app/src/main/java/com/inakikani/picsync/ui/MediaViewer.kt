package com.inakikani.picsync.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.ui.res.painterResource
import com.inakikani.picsync.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inakikani.picsync.data.AppLog
import com.inakikani.picsync.data.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Full-screen viewer over [items] (what the grid currently shows), opened at
 * [startId]. Swipe between items; photos zoom with pinch or double-tap; videos
 * play with the standard controls. Tap a photo to show/hide the info bar.
 * Back or ✕ closes it.
 */
@Composable
fun MediaViewer(items: List<MediaItem>, startId: Long, state: UiState, vm: AppViewModel, onClose: () -> Unit) {
    val start = remember(startId) { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { items.size }
    var showInfo by remember { mutableStateOf(true) }
    LaunchedEffect(startId) { AppLog.d("viewer: opened at ${items.getOrNull(start)?.name}") }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                key = { items[it].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                if (item.isVideo) {
                    VideoPage(item, active = page == pager.currentPage, vm = vm)
                } else {
                    PhotoPage(item, vm = vm, onTap = { showInfo = !showInfo })
                }
            }
            val current = items.getOrNull(pager.currentPage)
            if (current != null && (showInfo || current.isVideo)) {
                InfoBar(current, state, onToggleSelect = { vm.toggle(current.id) }, onClose = onClose)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PhotoPage(item: MediaItem, vm: AppViewModel, onTap: () -> Unit) {
    // The cached thumbnail shows at once; the sharp version replaces it when decoded.
    val thumb by produceState<ImageBitmap?>(null, item.id) { value = vm.thumbnail(item) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val full by produceState<ImageBitmap?>(null, item.id, w, h) { value = vm.fullImage(item, w, h) }

        var scale by remember(item.id) { mutableFloatStateOf(1f) }
        var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = (s - 1f) * w / 2f
            val maxY = (s - 1f) * h / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        val zoom = rememberTransformableState { change, pan, _ ->
            scale = (scale * change).coerceIn(1f, 5f)
            offset = clamp(offset + pan, scale)
        }

        val img = full ?: thumb
        if (img == null) {
            LoadingIndicator(color = Color.White)
        } else {
            Image(
                img,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(item.id) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    }
                    // Panning only while zoomed in, so a normal swipe goes to the pager.
                    .transformable(zoom, canPan = { scale > 1f })
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

@Composable
private fun VideoPage(item: MediaItem, active: Boolean, vm: AppViewModel) {
    if (!active) {
        // Only the page on screen plays; neighbours show their thumbnail.
        val thumb by produceState<ImageBitmap?>(null, item.id) { value = vm.thumbnail(item) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = "Video", tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
        return
    }
    var controller by remember(item.id) { mutableStateOf<MediaController?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val mc = MediaController(ctx)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                    controller = mc
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, what, extra ->
                        AppLog.w("viewer: can't play ${item.name} (id ${item.id}, error $what/$extra)")
                        false // shows Android's "Can't play this video"
                    }
                    setVideoURI(item.uri)
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier.wrapContentSize(), // VideoView keeps the video's aspect ratio
        )
        // Taps show the playback controls; swipes still reach the pager (a VideoView
        // would otherwise take every touch).
        Box(
            Modifier.fillMaxSize().pointerInput(item.id) {
                detectTapGestures(onTap = { controller?.show() })
            },
        )
    }
}

@Composable
private fun InfoBar(item: MediaItem, state: UiState, onToggleSelect: () -> Unit, onClose: () -> Unit) {
    val shade = Color.Black.copy(alpha = 0.55f)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(shade, Color.Transparent)))
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Color.White, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1)
                Text(
                    "${takenLabel(item.takenAt)} · ${"%.1f".format(Locale.getDefault(), item.size / 1e6)} MB",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", tint = Color.White)
            }
        }
        Box(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, shade)))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val id = item.id
            val status = when {
                id in state.excluded -> "Skipped (long-press it in the grid to undo)"
                id in state.records -> "On the Mac as ${state.records.getValue(id).serverName}"
                id in state.failed -> state.failed.getValue(id)
                else -> "Not uploaded yet"
            }
            Text(status, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            val canSelect = id !in state.excluded && state.upload == null
            if (canSelect) {
                FilledTonalButton(onClick = onToggleSelect, shapes = ButtonDefaults.shapes()) {
                    if (id in state.selected) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Selected")
                    } else {
                        Text("Select")
                    }
                }
            }
        }
    }
}

private val takenFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun takenLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(takenFormat.withLocale(Locale.getDefault()))

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vmodal.sdk.examples.framebase

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.launch

private val Paper = Color(0xFFECE9E2)
private val Ink = Color(0xFF252C29)
private val Rust = Color(0xFFAA4F2D)
private val Secondary = Color(0xFF706F67)
private val SearchField = Color(0xFFDFDCD4)
private val MissingFrame = Color(0xFFE0E3DA)

private enum class FramebasePage { LIBRARY, SEARCH, HISTORY }

private data class PendingPlayback(
    val clipId: String,
    val match: FrameMatch? = null,
    val moments: List<FrameMatch> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FramebaseScreen(viewModel: FramebaseViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(FramebasePage.LIBRARY) }
    var settingsOpen by remember { mutableStateOf(false) }
    var prepareOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var playbackClip by remember { mutableStateOf<ArchiveClip?>(null) }
    var playbackMatch by remember { mutableStateOf<FrameMatch?>(null) }
    var playbackMoments by remember { mutableStateOf<List<FrameMatch>>(emptyList()) }
    var pendingPlayback by remember { mutableStateOf<PendingPlayback?>(null) }
    val settingsState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importVideo(uri)
    }
    LaunchedEffect(state.connected, state.connecting) {
        if (state.connected && !state.connecting && settingsOpen) settingsOpen = false
    }
    LaunchedEffect(state.archive.clips, state.playbackBusy, state.playbackError, pendingPlayback) {
        val pending = pendingPlayback ?: return@LaunchedEffect
        if (state.playbackBusy) return@LaunchedEffect
        if (state.playbackError.isNotBlank()) {
            pendingPlayback = null
            return@LaunchedEffect
        }
        val readyClip = state.clips.firstOrNull { it.id == pending.clipId }
        if (!readyClip?.localPath.isNullOrBlank()) {
            playbackClip = readyClip
            playbackMatch = pending.match
            playbackMoments = pending.moments
            pendingPlayback = null
        }
    }
    fun requestPlayback(clip: ArchiveClip, match: FrameMatch? = null, moments: List<FrameMatch> = emptyList()) {
        val current = state.clips.firstOrNull { it.id == clip.id } ?: clip
        if (!current.localPath.isNullOrBlank()) {
            playbackClip = current
            playbackMatch = match
            playbackMoments = moments
        } else {
            pendingPlayback = PendingPlayback(clip.id, match, moments)
            viewModel.preparePlayback(clip.id)
        }
    }

    when (page) {
        FramebasePage.LIBRARY -> LibraryPage(
            state = state,
            onImport = { picker.launch(arrayOf("video/mp4")) },
            onMenu = { menuOpen = true }, menuOpen = menuOpen,
            onDismissMenu = { menuOpen = false },
            onSettings = { menuOpen = false; settingsOpen = true },
            onPrepare = {
                menuOpen = false
                if (!state.connected) settingsOpen = true
                else if (state.pendingJobId.isNotBlank()) viewModel.resumeIndex()
                else prepareOpen = true
            },
            onHistory = { menuOpen = false; page = FramebasePage.HISTORY },
            onSearch = { page = FramebasePage.SEARCH },
            onPlayback = { clip -> requestPlayback(clip) },
            onStop = viewModel::stopWork,
        )
        FramebasePage.SEARCH -> SearchShell(
            state = state,
            onBack = { page = FramebasePage.LIBRARY },
            onQuery = viewModel::setQuery,
            onSearch = { query ->
                if (!state.connected) settingsOpen = true
                else if (!state.ready) {
                    if (state.pendingJobId.isNotBlank()) viewModel.resumeIndex() else prepareOpen = true
                } else viewModel.search(query)
            },
            onCancel = viewModel::cancelSearch,
            onOpenSettings = { settingsOpen = true },
            onLooseMatches = viewModel::setLooseMatches,
            onOpenPlayback = { clip, match, moments -> requestPlayback(clip, match, moments) },
        )
        FramebasePage.HISTORY -> HistoryPage(state = state, onBack = { page = FramebasePage.LIBRARY }, onStop = viewModel::stopWork)
    }

    if (settingsOpen) SearchSettingsSheet(
        state = state, sheetState = settingsState, onDismiss = { settingsOpen = false },
        onConnect = viewModel::connect, onDisconnect = viewModel::disconnect,
    )
    if (prepareOpen) PrepareDialog(state = state, onDismiss = { prepareOpen = false }, onPrepare = { prepareOpen = false; viewModel.prepare() })
    playbackClip?.let { clip ->
        PlaybackBottomSheet(
            clip = clip,
            match = playbackMatch,
            moments = playbackMoments,
            onClose = {
                playbackClip = null
                playbackMatch = null
                playbackMoments = emptyList()
            },
        )
    }
}

@Composable
private fun LibraryPage(
    state: FramebaseUiState,
    onImport: () -> Unit,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onSettings: () -> Unit,
    onPrepare: () -> Unit,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onPlayback: (ArchiveClip) -> Unit,
    onStop: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize().background(Paper), contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = Paper.copy(alpha = .96f)) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(); Spacer(Modifier.width(10.dp))
                    Text("Framebase", style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.testTag("import_video").semantics { contentDescription = "Add video" }) { Icon(Icons.Default.Add, "Add video") }
                    Box {
                        IconButton(onClick = onMenu, modifier = Modifier.testTag("library_menu").semantics { contentDescription = "More options" }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                            DropdownMenuItem(text = { Text("Search settings") }, onClick = onSettings, leadingIcon = { Icon(Icons.Default.Settings, null) }, modifier = Modifier.testTag("menu_search_settings"))
                            DropdownMenuItem(text = { Text(if (state.pendingJobId.isNotBlank()) "Resume preparation" else "Prepare videos for search") }, onClick = onPrepare, leadingIcon = { Icon(Icons.Default.Send, null) }, modifier = Modifier.testTag("menu_prepare"))
                            DropdownMenuItem(text = { Text("Sync history") }, onClick = onHistory, leadingIcon = { Icon(Icons.Default.Refresh, null) }, modifier = Modifier.testTag("menu_history"))
                        }
                    }
                }
            }
        },
        bottomBar = { SearchDock(onClick = onSearch, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("library_screen"),
            contentPadding = PaddingValues(start = 16.dp, top = padding.calculateTopPadding() + 12.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Street footage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text("${state.clips.size} videos", color = Secondary) } }
            items(state.clips, key = { it.id }) { clip -> VideoCard(clip, onClick = { onPlayback(clip) }) }
            if (state.busy) item { WorkStatus(state, onStop) }
            if (state.playbackBusy) item {
                Column(Modifier.fillMaxWidth().testTag("playback_copy_status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Opening video…", color = Secondary)
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("playback_copy_progress"))
                }
            }
            if (state.connected && !state.ready && !state.busy) item { TextButton(onClick = onPrepare, modifier = Modifier.fillMaxWidth().testTag("prepare_videos")) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text("Prepare videos for search") } }
            if (state.connected && state.ready && state.clips.any { !it.bundled && !it.uploaded } && !state.busy) item { TextButton(onClick = onPrepare, modifier = Modifier.fillMaxWidth().testTag("prepare_added_videos")) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text("Prepare added videos") } }
            val notice = state.error.ifBlank { state.playbackError.ifBlank { state.notice } }
            if (notice.isNotBlank()) item { Text(notice, color = if (state.error.isNotBlank() || state.playbackError.isNotBlank()) MaterialTheme.colorScheme.error else Secondary, modifier = Modifier.testTag("library_notice")) }
        }
    }
}

@Composable
private fun BrandMark() {
    Canvas(Modifier.size(24.dp).semantics { contentDescription = "Framebase mark" }) {
        val stroke = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Square)
        val p = Path().apply {
            moveTo(2.dp.toPx(), 9.dp.toPx()); lineTo(2.dp.toPx(), 2.dp.toPx()); lineTo(9.dp.toPx(), 2.dp.toPx())
            moveTo(15.dp.toPx(), 2.dp.toPx()); lineTo(22.dp.toPx(), 2.dp.toPx()); lineTo(22.dp.toPx(), 9.dp.toPx())
            moveTo(22.dp.toPx(), 15.dp.toPx()); lineTo(22.dp.toPx(), 22.dp.toPx()); lineTo(15.dp.toPx(), 22.dp.toPx())
            moveTo(9.dp.toPx(), 22.dp.toPx()); lineTo(2.dp.toPx(), 22.dp.toPx()); lineTo(2.dp.toPx(), 15.dp.toPx())
            moveTo(9.dp.toPx(), 8.dp.toPx()); lineTo(15.dp.toPx(), 12.dp.toPx()); lineTo(9.dp.toPx(), 16.dp.toPx())
        }
        drawPath(p, Rust, style = stroke)
    }
}

@Composable
private fun SearchDock(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clickable(role = Role.Button, onClick = onClick).testTag("search_dock").semantics { contentDescription = "Search your videos" }, shape = RoundedCornerShape(22.dp), color = Ink, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag("open_search").padding(start = 20.dp, top = 10.dp, end = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = Color.White); Spacer(Modifier.width(12.dp)); Text("Search your videos", color = Color.White, modifier = Modifier.weight(1f))
            Surface(color = Color(0xFFE6A17F), shape = RoundedCornerShape(13.dp), modifier = Modifier.size(42.dp)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowForward, "Open search", tint = Ink) } }
        }
    }
}

@Composable
private fun VideoCard(clip: ArchiveClip, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().aspectRatio(1.8f).testTag("video_card_${clip.id}").semantics { contentDescription = "Play ${clip.title}" }.clickable(role = Role.Button, onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MissingFrame)) {
        Box(Modifier.fillMaxSize()) {
            if (!clip.posterAssetPath.isNullOrBlank()) SubcomposeAsyncImage(model = "file:///android_asset/${clip.posterAssetPath}", contentDescription = "${clip.title} poster", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), loading = { MissingPoster() }, error = { MissingPoster() }) else MissingPoster(movie = true)
            Box(Modifier.fillMaxSize().background(Color(0x6619201C)))
            Surface(color = Color(0xCC252C29), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Text(timeLabel(clip.durationSec), color = Color.White, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) }
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp).padding(end = 60.dp)) { Text(clip.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(4.dp)); Text(clip.city, color = Color(0xFFDFE5DF), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Surface(color = Color(0xCC252C29), shape = RoundedCornerShape(13.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(44.dp)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, "Play ${clip.title}", tint = Color.White) } }
        }
    }
}

@Composable
private fun MissingPoster(movie: Boolean = false) { Box(Modifier.fillMaxSize().background(MissingFrame), contentAlignment = Alignment.Center) { Icon(Icons.Default.Info, if (movie) "No movie preview" else "No preview", tint = Secondary, modifier = Modifier.size(30.dp)) } }

@Composable
private fun WorkStatus(state: FramebaseUiState, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("work_status"), colors = CardDefaults.cardColors(containerColor = Color(0xFFE0DDD4))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.phase.ifBlank { "Preparing videos" }, style = MaterialTheme.typography.titleMedium)
            if (state.progress != null) LinearProgressIndicator(progress = state.progress.toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().testTag("upload_progress")) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("work_progress"))
            TextButton(onClick = onStop, modifier = Modifier.testTag("work_cancel")) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text(if (state.workPhase == FramebaseWorkPhase.POLLING_INDEX) "Stop waiting" else "Cancel") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSettingsSheet(
    state: FramebaseUiState,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    // Deliberately remember, rather than rememberSaveable: credentials must not enter saved state.
    var apiKey by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Paper, modifier = Modifier.testTag("settings_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Search settings", style = MaterialTheme.typography.headlineSmall)
            Text(if (state.connected) "Connected to the Framebase street archive." else "Connect a VModal API key to prepare and search your videos.", color = Secondary)
            if (!state.connected) {
                Box(Modifier.fillMaxWidth().testTag("api_key")) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API key") },
                        singleLine = true,
                        enabled = !state.connecting && !state.busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("api_key_field").semantics { contentDescription = "API key" },
                    )
                }
                Button(onClick = { val value = apiKey; apiKey = ""; focus.clearFocus(); onConnect(value) }, enabled = apiKey.isNotBlank() && !state.connecting && !state.busy, modifier = Modifier.fillMaxWidth().testTag("connect_button")) {
                    if (state.connecting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("Connect")
                }
            } else {
                OutlinedButton(onClick = onDisconnect, enabled = !state.connecting && !state.busy, modifier = Modifier.fillMaxWidth().testTag("disconnect_button")) { Text("Disconnect") }
            }
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings_error"))
        }
    }
}

@Composable
private fun PrepareDialog(state: FramebaseUiState, onDismiss: () -> Unit, onPrepare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prepare videos for search?") },
        text = { Text(if (state.hasPendingUploads) "${state.clips.count { !it.uploaded }} videos will upload and receive a visual index." else "The existing videos' visual index will rebuild.") },
        confirmButton = { Button(onClick = onPrepare, modifier = Modifier.testTag("confirm_prepare")) { Text("Prepare") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_prepare")) { Text("Cancel") } },
        modifier = Modifier.testTag("prepare_dialog"),
    )
}

@Composable
private fun SearchShell(
    state: FramebaseUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onLooseMatches: (Boolean) -> Unit,
    onOpenPlayback: (ArchiveClip, FrameMatch, List<FrameMatch>) -> Unit,
) {
    var query by remember(state.activeQuery) { mutableStateOf(state.activeQuery) }
    var optionsOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val expandedVideos = remember { mutableStateOf<Set<String>>(emptySet()) }
    val focus = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val groups = remember(state.searchBatch) { groupMoments(state.searchBatch?.matches.orEmpty()) }
    LaunchedEffect(state.activeQuery, state.searchBatch) {
        expandedVideos.value = emptySet()
    }
    LaunchedEffect(state.playbackError) {
        if (state.playbackError.isNotBlank()) snackbarHostState.showSnackbar(state.playbackError)
    }
    fun submit() {
        focus.clearFocus()
        onSearch(query)
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().background(Paper), contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("search_back").semantics { contentDescription = "Back to videos" }) { Icon(Icons.Default.ArrowBack, "Back to videos") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; onQuery(it) },
                    placeholder = { Text("Describe a moment") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.weight(1f).testTag("search_field"),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = SearchField,
                        focusedContainerColor = SearchField,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Rust,
                    ),
                )
                IconButton(onClick = { if (state.searching) onCancel() else submit() }, modifier = Modifier.testTag("run_search").semantics { contentDescription = if (state.searching) "Cancel search" else "Search" }) { Icon(if (state.searching) Icons.Default.Close else Icons.Default.Search, if (state.searching) "Cancel search" else "Search") }
                IconButton(onClick = { optionsOpen = true }, modifier = Modifier.testTag("search_options").semantics { contentDescription = "Search options" }) { Icon(Icons.Default.MoreVert, "Search options") }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.connecting || state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("search_progress"))
            if (!state.connected) {
                Text("Connect to search your local archive.", color = Secondary)
                Button(onClick = onOpenSettings, modifier = Modifier.testTag("search_open_settings")) { Text("Open Search settings") }
            } else if (!state.ready) {
                Text("Prepare your videos before searching.", color = Secondary)
                TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("search_prepare_settings")) { Text("Open Search settings") }
            } else if (state.searchBatch == null && !state.searching) {
                Text("Try a search", style = MaterialTheme.typography.titleLarge)
                SEARCH_SUGGESTIONS.forEachIndexed { index, suggestion ->
                    OutlinedButton(onClick = { query = suggestion; onQuery(suggestion); focus.clearFocus(); onSearch(suggestion) }, modifier = Modifier.fillMaxWidth().testTag("suggestion_$index").semantics { contentDescription = "Search suggestion: $suggestion" }) { Text(suggestion, modifier = Modifier.fillMaxWidth()) }
                }
            } else if (state.searchBatch != null && groups.isEmpty()) {
                EmptySearchState(
                    showLooseAction = state.maxDistance <= FOCUSED_DISTANCE,
                    onLooseMatches = {
                        onLooseMatches(true)
                        submit()
                    },
                )
            } else if (groups.isNotEmpty()) {
                Text("${groups.size} ${if (groups.size == 1) "video" else "videos"}", color = Secondary, modifier = Modifier.testTag("result_video_count"))
                groups.forEach { (filename, moments) ->
                    val clip = clipFor(state.clips, filename)
                    Text(clip?.title ?: filename, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("result_group_$filename"))
                    Text(clip?.city ?: "Not stored on this device", color = Secondary, modifier = Modifier.testTag("result_source_$filename"))
                    MomentGrid(
                        filename = filename,
                        moments = moments,
                        expanded = expandedVideos.value.contains(filename),
                        onToggleExpanded = {
                            expandedVideos.value = if (expandedVideos.value.contains(filename)) {
                                expandedVideos.value - filename
                            } else {
                                expandedVideos.value + filename
                            }
                        },
                        onMoment = { match ->
                            if (clip == null) {
                                scope.launch { snackbarHostState.showSnackbar("This video is not stored on this device.") }
                            } else {
                                onOpenPlayback(clip, match, moments)
                            }
                        },
                    )
                }
            }
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("search_error"))
        }
    }
    if (optionsOpen) {
        SearchOptionsSheet(
            looseMatches = state.maxDistance > FOCUSED_DISTANCE,
            onLooseMatches = onLooseMatches,
            onDetails = { optionsOpen = false; detailsOpen = true },
            onDismiss = { optionsOpen = false },
        )
    }
    if (detailsOpen) {
        SearchDetailsSheet(batch = state.searchBatch, onDismiss = { detailsOpen = false })
    }
}

private val SEARCH_SUGGESTIONS = listOf(
    "People crossing the street",
    "A bus on a city street",
    "Cars at an intersection",
)

@Composable
private fun EmptySearchState(showLooseAction: Boolean, onLooseMatches: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 42.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("No matching moments", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("empty_search"))
        Text("Try a different description.", color = Secondary)
        if (showLooseAction) TextButton(onClick = onLooseMatches, modifier = Modifier.testTag("include_looser_empty")) { Text("Include looser matches") }
    }
}

@Composable
private fun MomentGrid(
    filename: String,
    moments: List<FrameMatch>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onMoment: (FrameMatch) -> Unit,
) {
    val count = if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 2
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val visible = if (expanded) moments else moments.take(2)
        visible.chunked(count).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { columnIndex, match ->
                    MomentTile(
                        filename = filename,
                        index = rowIndex * count + columnIndex,
                        match = match,
                        onClick = { onMoment(match) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(count - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (moments.size > 2) {
            TextButton(onClick = onToggleExpanded, modifier = Modifier.testTag("expand_$filename").semantics { contentDescription = if (expanded) "Show fewer moments" else "Show ${moments.size - 2} more moments" }) {
                Text(if (expanded) "Show fewer moments" else "Show ${moments.size - 2} more moments")
            }
        }
    }
}

@Composable
private fun MomentTile(
    filename: String,
    index: Int,
    match: FrameMatch,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1.6f)
            .background(MissingFrame, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("moment_${filename}_$index")
            .semantics { contentDescription = "Play at ${match.seconds?.let(::timeLabel) ?: "unknown time"}" },
    ) {
        if (match.imageBytes != null) {
            SubcomposeAsyncImage(
                model = match.imageBytes,
                contentDescription = "Matched frame",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { MissingPoster() },
                error = { MissingPoster() },
            )
        } else if (match.imageUrl?.startsWith("https://") == true) {
            SubcomposeAsyncImage(
                model = match.imageUrl,
                contentDescription = "Matched frame",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { MissingPoster() },
                error = { MissingPoster() },
            )
        } else {
            MissingPoster()
        }
        if (match.seconds != null) {
            Surface(color = Color(0xCC252C29), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp)) {
                Text(timeLabel(match.seconds), color = Color.White, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun SearchOptionsSheet(
    looseMatches: Boolean,
    onLooseMatches: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper, modifier = Modifier.testTag("search_options_sheet")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Include looser matches") },
                supportingContent = { Text("Useful if the first search misses something.") },
                trailingContent = { Switch(checked = looseMatches, onCheckedChange = onLooseMatches, modifier = Modifier.testTag("loose_matches")) },
            )
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Search details") },
                leadingContent = { Icon(Icons.Default.Info, null) },
                modifier = Modifier.clickable(onClick = onDetails).testTag("search_details"),
            )
        }
    }
}

@Composable
private fun SearchDetailsSheet(batch: SearchBatch?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper, modifier = Modifier.testTag("search_details_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 4.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Search details", style = MaterialTheme.typography.headlineSmall)
            if (batch == null) {
                Text("Run a search first.", modifier = Modifier.testTag("details_before_search"))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("details_metrics")) {
                    Text("Server results: ${batch.total}")
                    Text("Within distance filter: ${batch.matches.size}")
                    Text("Search request: ${batch.roundTripMs} ms")
                    Text("Server execution: ${batch.serverMs.toLong()} ms")
                    Text("Frame retrieval: ${batch.imageMs} ms")
                }
                Text("Nearby frames from the same video are combined in the results. Distance is similarity, not confidence.")
            }
        }
    }
}

@Composable
private fun HistoryPage(state: FramebaseUiState, onBack: () -> Unit, onStop: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize().background(Paper), contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        TopAppBar(title = { Text("Sync history") }, navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.testTag("history_back")) { Icon(Icons.Default.ArrowBack, "Back to videos") } })
    }) { padding ->
        if (state.events.isEmpty() && !state.busy) {
            Box(Modifier.fillMaxSize().padding(padding).padding(20.dp), contentAlignment = Alignment.Center) { Text("No uploads or searches yet.", color = Secondary, modifier = Modifier.testTag("history_empty")) }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("history_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.busy) item { WorkStatus(state, onStop) }
                if (state.events.isEmpty()) item { Text("No uploads or searches yet.", color = Secondary, modifier = Modifier.testTag("history_empty")) }
                items(state.events) { event ->
                    androidx.compose.material3.ListItem(headlineContent = { Text(event.title) }, supportingContent = { Text(event.detail) }, leadingContent = { Icon(if (event.isError) Icons.Default.Warning else Icons.Default.CheckCircle, null, tint = if (event.isError) MaterialTheme.colorScheme.error else Rust) }, modifier = Modifier.testTag("history_${event.title.hashCode()}"))
                    Divider()
                }
            }
        }
    }
}

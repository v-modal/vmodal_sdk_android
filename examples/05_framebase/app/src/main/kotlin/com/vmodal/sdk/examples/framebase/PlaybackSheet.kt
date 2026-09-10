package com.vmodal.sdk.examples.framebase

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.delay

private val PlaybackInk = Color(0xFF252C29)
private val PlaybackSecondary = Color(0xFF706F67)
private val PlaybackAccent = Color(0xFFAA4F2D)
private val PlaybackDark = Color(0xDD252C29)

const val PLAYBACK_CLOSE_TAG = "playback_close"
const val PLAYBACK_VIDEO_TAG = "playback_video"
const val PLAYBACK_PLAY_PAUSE_TAG = "playback_play_pause"
const val PLAYBACK_SEEK_TAG = "playback_seek"
const val PLAYBACK_MOMENTS_TAG = "playback_other_moments"

enum class PlaybackStatus {
    LOADING,
    READY,
    PLAYING,
    PAUSED,
    ERROR,
}

data class PlaybackSnapshot(
    val status: PlaybackStatus = PlaybackStatus.LOADING,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
) {
    val positionSeconds: Double get() = positionMs.coerceAtLeast(0L) / 1000.0
    val durationSeconds: Double get() = durationMs.coerceAtLeast(0L) / 1000.0
}

/** Small seam used by the sheet and by JVM tests; it deliberately exposes no Activity. */
interface PlaybackPlayer {
    val snapshot: PlaybackSnapshot
    val media3Player: Player?
        get() = null

    fun addListener(listener: () -> Unit)
    fun removeListener(listener: () -> Unit)
    fun prepare(localPath: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}

fun interface PlaybackPlayerFactory {
    fun create(context: Context): PlaybackPlayer
}

/** Media3 adapter. The app-private path is the only source accepted by this example. */
class Media3PlaybackPlayer(context: Context) : PlaybackPlayer {
    private val exoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private val listeners = mutableListOf<() -> Unit>()
    private var released = false
    private var currentError: String? = null

    override val media3Player: Player get() = exoPlayer

    override val snapshot: PlaybackSnapshot
        get() {
            val state = when {
                currentError != null -> PlaybackStatus.ERROR
                exoPlayer.playbackState == Player.STATE_READY && exoPlayer.isPlaying -> PlaybackStatus.PLAYING
                exoPlayer.playbackState == Player.STATE_READY -> PlaybackStatus.PAUSED
                else -> PlaybackStatus.LOADING
            }
            return PlaybackSnapshot(
                status = state,
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                durationMs = exoPlayer.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
                error = currentError,
            )
        }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = notifyListeners()
        override fun onIsPlayingChanged(isPlaying: Boolean) = notifyListeners()
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = notifyListeners()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = notifyListeners()

        override fun onPlayerError(error: PlaybackException) {
            currentError = "This video could not be opened."
            notifyListeners()
        }
    }

    init {
        exoPlayer.addListener(playerListener)
    }

    override fun addListener(listener: () -> Unit) {
        if (!released) listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    override fun prepare(localPath: String) {
        if (released) return
        currentError = null
        if (localPath.isBlank()) {
            currentError = "This video could not be opened."
            notifyListeners()
            return
        }
        try {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(localPath))))
            exoPlayer.prepare()
            notifyListeners()
        } catch (_: RuntimeException) {
            currentError = "This video could not be opened."
            notifyListeners()
        }
    }

    override fun play() {
        if (!released && exoPlayer.playbackState == Player.STATE_READY) exoPlayer.play()
    }

    override fun pause() {
        if (!released) exoPlayer.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val duration = snapshot.durationMs
        if (duration <= 0L) return
        exoPlayer.seekTo(positionMs.coerceIn(0L, duration))
    }

    override fun release() {
        if (released) return
        released = true
        listeners.clear()
        exoPlayer.removeListener(playerListener)
        exoPlayer.pause()
        exoPlayer.release()
    }

    private fun notifyListeners() {
        if (!released) listeners.toList().forEach { it() }
    }
}

fun defaultPlaybackPlayerFactory(): PlaybackPlayerFactory = PlaybackPlayerFactory { context ->
    Media3PlaybackPlayer(context)
}

private val StableDefaultPlaybackPlayerFactory = defaultPlaybackPlayerFactory()

fun safeInitialSeekMs(seconds: Double?, durationMs: Long): Long? {
    if (seconds == null || !seconds.isFinite() || seconds < 0.0 || durationMs <= 0L) return null
    val target = (seconds * 1000.0).toLong()
    return target.takeIf { it >= 0L && it <= durationMs }
}

fun safeSeekMs(seconds: Double?, durationMs: Long): Long? = safeInitialSeekMs(seconds, durationMs)

fun playbackMomentActive(currentPositionMs: Long, momentSeconds: Double?): Boolean {
    if (momentSeconds == null || !momentSeconds.isFinite() || momentSeconds < 0.0) return false
    return abs(currentPositionMs.coerceAtLeast(0L) / 1000.0 - momentSeconds) < 3.0
}

fun isPlaybackMomentActive(currentPositionMs: Long, momentSeconds: Double?): Boolean =
    playbackMomentActive(currentPositionMs, momentSeconds)

fun playbackTimeLabel(milliseconds: Long): String = timeLabel(milliseconds.coerceAtLeast(0L) / 1000.0)

fun formatPlaybackTime(milliseconds: Long): String = playbackTimeLabel(milliseconds)

/** Coordinates safe initial/moment seeks without coupling tests to Media3. */
class PlaybackSession(
    private val player: PlaybackPlayer,
    private val initialSeconds: Double? = null,
) {
    private var initialSeekApplied = false

    fun initialize(localPath: String?) {
        player.prepare(localPath.orEmpty())
    }

    fun onReady() {
        if (initialSeekApplied) return
        initialSeekApplied = true
        safeInitialSeekMs(initialSeconds, player.snapshot.durationMs)?.let(player::seekTo)
    }

    fun seekToMoment(seconds: Double?) {
        safeSeekMs(seconds, player.snapshot.durationMs)?.let(player::seekTo)
    }

    fun togglePlayback() {
        if (player.snapshot.status == PlaybackStatus.PLAYING) player.pause() else player.play()
    }

    fun seekToSlider(positionMs: Long) {
        val duration = player.snapshot.durationMs
        if (duration > 0L) player.seekTo(positionMs.coerceIn(0L, duration))
    }

    fun release() = player.release()
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaybackSheet(
    clip: ArchiveClip,
    match: FrameMatch? = null,
    moments: List<FrameMatch> = emptyList(),
    onClose: () -> Unit,
    playerFactory: PlaybackPlayerFactory = StableDefaultPlaybackPlayerFactory,
) {
    val context = LocalContext.current
    val player = remember(playerFactory) { playerFactory.create(context) }
    val session = remember(player, match?.seconds, clip.localPath) {
        PlaybackSession(player, match?.seconds)
    }
    var snapshot by remember(player) { mutableStateOf(player.snapshot) }
    val listener = remember(player) { { snapshot = player.snapshot } }

    DisposableEffect(player, clip.localPath) {
        player.addListener(listener)
        session.initialize(clip.localPath)
        onDispose {
            player.removeListener(listener)
            session.release()
        }
    }
    LaunchedEffect(snapshot.status, snapshot.durationMs) {
        if (snapshot.status == PlaybackStatus.READY || snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.PAUSED) {
            session.onReady()
            snapshot = player.snapshot
        }
    }
    LaunchedEffect(player, snapshot.status == PlaybackStatus.PLAYING) {
        while (player.snapshot.status == PlaybackStatus.PLAYING) {
            delay(250L)
            snapshot = player.snapshot
        }
    }

    val duration = snapshot.durationMs
    val position = snapshot.positionMs.coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
    val aspect = player.media3Player?.videoSize?.let { size ->
        if (size.width > 0 && size.height > 0) size.width.toFloat() / size.height else 16f / 9f
    } ?: 16f / 9f
    val viewPlayer = player.media3Player

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(clip.title, color = PlaybackInk, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(clip.city, color = PlaybackSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(
                onClick = {
                    player.pause()
                    onClose()
                },
                modifier = Modifier.testTag(PLAYBACK_CLOSE_TAG),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close recording", tint = PlaybackInk)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFD8D5CE))
                .aspectRatio(aspect),
        ) {
            if (viewPlayer != null && snapshot.status in setOf(
                    PlaybackStatus.READY,
                    PlaybackStatus.PLAYING,
                    PlaybackStatus.PAUSED,
                )) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = viewPlayer
                            useController = false
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .matchParentSize()
                        .testTag(PLAYBACK_VIDEO_TAG),
                )
            } else {
                ClipPoster(clip, Modifier.fillMaxWidth().testTag(PLAYBACK_VIDEO_TAG))
            }

            when {
                snapshot.status == PlaybackStatus.LOADING -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = PlaybackAccent,
                )

                snapshot.status == PlaybackStatus.READY || snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.PAUSED -> {
                    PlaybackControls(
                        snapshot = snapshot,
                        onToggle = { session.togglePlayback(); snapshot = player.snapshot },
                        onSeek = { session.seekToSlider(it); snapshot = player.snapshot },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (snapshot.status == PlaybackStatus.ERROR) {
            Text(
                text = "This video could not be opened.",
                color = PlaybackInk,
                modifier = Modifier.testTag("playback_error"),
            )
        }

        if (moments.size > 1) {
            Text("Other moments", color = PlaybackSecondary, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag(PLAYBACK_MOMENTS_TAG),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                moments.forEachIndexed { index, moment ->
                    val active = playbackMomentActive(position, moment.seconds)
                    MomentPreview(
                        match = moment,
                        active = active,
                        onClick = {
                            session.seekToMoment(moment.seconds)
                            snapshot = player.snapshot
                        },
                        modifier = Modifier.testTag("playback_moment_$index"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackBottomSheet(
    clip: ArchiveClip,
    match: FrameMatch? = null,
    moments: List<FrameMatch> = emptyList(),
    onClose: () -> Unit,
    playerFactory: PlaybackPlayerFactory = StableDefaultPlaybackPlayerFactory,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose) {
        PlaybackSheet(clip, match, moments, onClose, playerFactory)
    }
}

@Composable
private fun PlaybackControls(
    snapshot: PlaybackSnapshot,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = snapshot.durationMs.coerceAtLeast(1L)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        color = PlaybackDark,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .testTag(PLAYBACK_PLAY_PAUSE_TAG)
                    .semantics {
                        contentDescription = if (snapshot.status == PlaybackStatus.PLAYING) "Pause" else "Play"
                    },
            ) {
                if (snapshot.status == PlaybackStatus.PLAYING) {
                    Text("Ⅱ", color = Color.White, style = MaterialTheme.typography.titleLarge)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                }
            }
            Slider(
                value = snapshot.positionMs.coerceIn(0L, duration).toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .testTag(PLAYBACK_SEEK_TAG)
                    .semantics { contentDescription = "Seek video" },
            )
            Text(
                "${playbackTimeLabel(snapshot.positionMs)} / ${playbackTimeLabel(snapshot.durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ClipPoster(clip: ArchiveClip, modifier: Modifier = Modifier) {
    if (clip.posterAssetPath != null) {
        AsyncImage(
            model = "file:///android_asset/${clip.posterAssetPath}",
            contentDescription = "Poster for ${clip.title}",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFFD8D5CE)),
            contentAlignment = Alignment.Center,
        ) {
            Text("No preview", color = PlaybackSecondary)
        }
    }
}

@Composable
private fun MomentPreview(
    match: FrameMatch,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 118.dp, height = 86.dp)
            .clip(RoundedCornerShape(13.dp))
            .then(if (active) Modifier.border(BorderStroke(2.dp, PlaybackAccent), RoundedCornerShape(13.dp)) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Matched moment" }
            .padding(2.dp),
    ) {
        if (match.imageBytes != null) {
            AsyncImage(
                model = match.imageBytes,
                contentDescription = "Matched moment ${playbackTimeLabel((match.seconds ?: 0.0).toLong() * 1000L)}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFD8D5CE), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("No preview", color = PlaybackSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

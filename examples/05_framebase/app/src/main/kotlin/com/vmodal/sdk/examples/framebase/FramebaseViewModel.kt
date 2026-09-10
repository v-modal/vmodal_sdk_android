package com.vmodal.sdk.examples.framebase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.vmodal.sdk.ApiError
import com.vmodal.sdk.AuthError
import com.vmodal.sdk.SdkError
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadEvent
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The small store contract keeps ViewModel tests independent of Android file details. */
interface FramebaseStore {
    fun load(): ArchiveState
    fun save(state: ArchiveState): Boolean
    fun localFile(clip: ArchiveClip): File
    fun ensureBundledLocal(clip: ArchiveClip): ArchiveClip
    fun importVideo(uri: android.net.Uri, displayName: String? = null): ArchiveClip
}

private class ArchiveStoreAdapter(private val store: ArchiveStore) : FramebaseStore {
    override fun load(): ArchiveState = store.load()
    override fun save(state: ArchiveState): Boolean = store.save(state)
    override fun localFile(clip: ArchiveClip): File = store.localFile(clip)
    override fun ensureBundledLocal(clip: ArchiveClip): ArchiveClip = store.ensureBundledLocal(clip)
    override fun importVideo(uri: android.net.Uri, displayName: String?): ArchiveClip =
        store.importVideo(uri, displayName)
}

enum class FramebaseConnectionState {
    INITIALIZING,
    DISCONNECTED,
    CONNECTING,
    CONNECTED_NO_INDEX,
    SEARCH_READY,
}

enum class FramebaseWorkPhase {
    IDLE,
    UPLOADING,
    CREATING_INDEX,
    POLLING_INDEX,
}

/** Immutable state rendered by Compose. Credentials, upload sources, and SDK clients are absent. */
data class FramebaseUiState(
    val initialized: Boolean = false,
    val initializing: Boolean = true,
    val archive: ArchiveState = ArchiveState(),
    val connection: FramebaseConnectionState = FramebaseConnectionState.INITIALIZING,
    val accountId: String = "",
    val indexVersion: Int? = null,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val workPhase: FramebaseWorkPhase = FramebaseWorkPhase.IDLE,
    val phase: String = "",
    val busy: Boolean = false,
    val progress: Double? = null,
    val progressClipId: String = "",
    val searching: Boolean = false,
    val activeQuery: String = "",
    val maxDistance: Double = FOCUSED_DISTANCE,
    val searchBatch: SearchBatch? = null,
    val playbackBusy: Boolean = false,
    val playbackError: String = "",
    val notice: String = "",
    val error: String = "",
) {
    val clips: List<ArchiveClip> get() = archive.clips
    val events: List<ArchiveEvent> get() = archive.events
    val pendingJobId: String get() = archive.pendingJobId
    val hasPendingUploads: Boolean get() = clips.any { !it.uploaded }
    val searchReady: Boolean get() = connection == FramebaseConnectionState.SEARCH_READY
    val batch: SearchBatch? get() = searchBatch
    val progressFraction: Double? get() = progress
    val history: List<ArchiveEvent> get() = events
    val isPreparing: Boolean get() = busy
    val isConnecting: Boolean get() = connecting
}

/**
 * Owns Framebase preparation, account state, and ephemeral search results. The gateway is the only
 * object allowed to know the API key; this class never stores it in state, events, or exceptions.
 */
class FramebaseViewModel(
    private val store: FramebaseStore,
    private val gatewayFactory: (String) -> FramebaseGateway,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    constructor(store: ArchiveStore) : this(
        ArchiveStoreAdapter(store),
        { key -> SdkFramebaseGateway(key) },
        Dispatchers.IO,
        { millis -> delay(millis) },
        System::currentTimeMillis,
    )

    constructor(context: Context) : this(ArchiveStore(context.applicationContext))

    constructor(
        store: FramebaseStore,
        gateway: FramebaseGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        wait: suspend (Long) -> Unit = { millis -> delay(millis) },
        now: () -> Long = System::currentTimeMillis,
    ) : this(store, { gateway }, dispatcher, wait, now)

    private val mutableState = MutableStateFlow(FramebaseUiState())
    val state: StateFlow<FramebaseUiState> = mutableState.asStateFlow()

    private var archive = ArchiveState()
    private var gateway: FramebaseGateway? = null
    private var preparationJob: Job? = null
    private var searchJob: Job? = null
    private var preparationGeneration = 0L
    private var searchGeneration = 0L
    private var playbackJob: Job? = null
    private var playbackGeneration = 0L
    private var disposed = false

    init {
        viewModelScope.launch(dispatcher) {
            archive = try {
                store.load()
            } catch (_: Exception) {
                ArchiveState()
            }
            publish { copy(initialized = true, initializing = false, archive = archive, connection = FramebaseConnectionState.DISCONNECTED) }
        }
    }

    fun connect(apiKey: String) {
        val key = apiKey.trim()
        if (key.isBlank() || mutableState.value.busy || mutableState.value.connecting) {
            if (key.isBlank()) publish { copy(error = "An API key is required.") }
            return
        }
        val previous = mutableState.value
        invalidateSearch()
        mutableState.update {
            it.copy(
                connection = FramebaseConnectionState.CONNECTING,
                connecting = true,
                notice = "",
                error = "",
            )
        }
        viewModelScope.launch(dispatcher) {
            var next: FramebaseGateway? = null
            try {
                next = gatewayFactory(key)
                val result = next.connect()
                require(result.accountId.isNotBlank()) { "auth/me returned no user_id" }
                if (disposed) {
                    next.close()
                    return@launch
                }
                val accountChanged = result.accountId.isNotBlank() && archive.accountId != result.accountId
                if (accountChanged) {
                    archive = resetForAccount(archive, result.accountId)
                    saveArchive()
                } else if (archive.accountId != result.accountId) {
                    archive = archive.copy(accountId = result.accountId)
                    saveArchive()
                }
                val old = gateway
                gateway = next
                old?.close()
                mutableState.update {
                    it.copy(
                        connection = if (result.indexVersion == null) FramebaseConnectionState.CONNECTED_NO_INDEX else FramebaseConnectionState.SEARCH_READY,
                        connected = true,
                        accountId = result.accountId,
                        indexVersion = result.indexVersion,
                        ready = result.indexVersion != null,
                        notice = if (result.indexVersion == null) "Authenticated. No street index yet." else "Authenticated · street index available",
                        error = "",
                        archive = archive,
                    )
                }
                record(
                    "Connected",
                    if (result.indexVersion == null) "Authenticated. No street index yet."
                    else "Authenticated · street index available",
                )
            } catch (error: CancellationException) {
                next?.close()
                throw error
            } catch (error: Exception) {
                next?.close()
                mutableState.update {
                    it.copy(
                        connection = if (gateway == null) FramebaseConnectionState.DISCONNECTED else previous.connection,
                        connecting = false,
                        connected = if (gateway == null) false else previous.connected,
                        accountId = if (gateway == null) "" else previous.accountId,
                        indexVersion = if (gateway == null) null else previous.indexVersion,
                        ready = if (gateway == null) false else previous.ready,
                        error = safeFramebaseError(error),
                        notice = "",
                    )
                }
                record("Connection failed", safeFramebaseError(error), true)
            } finally {
                mutableState.update { it.copy(connecting = false) }
            }
        }
    }

    suspend fun connectAndWait(apiKey: String): Boolean {
        val before = mutableState.value
        connect(apiKey)
        while (mutableState.value.connecting && !disposed) wait(1)
        return mutableState.value.connected && mutableState.value.accountId != before.accountId ||
            mutableState.value.connected && apiKey.trim().isNotBlank()
    }

    fun disconnect() {
        if (mutableState.value.busy || mutableState.value.connecting) return
        invalidateSearch()
        val old = gateway
        gateway = null
        mutableState.update {
            it.copy(
                connection = FramebaseConnectionState.DISCONNECTED,
                connected = false,
                ready = false,
                indexVersion = null,
                accountId = "",
                notice = "Disconnected. Recordings remain on this device.",
                error = "",
            )
        }
        viewModelScope.launch(dispatcher) { old?.close() }
    }

    fun forgetApiKey() {
        if (mutableState.value.busy) stopWork()
        if (mutableState.value.connecting) return
        disconnect()
    }

    fun importVideo(uri: android.net.Uri, displayName: String? = null) {
        if (mutableState.value.busy) return
        invalidateSearch()
        viewModelScope.launch(dispatcher) {
            try {
                val clip = store.importVideo(uri, displayName)
                archive = archive.copy(clips = archive.clips + clip)
                saveArchive()
                record("Recording added", "${clip.title} · stored on device only")
                mutableState.update { it.copy(notice = "${clip.title} was added to the archive.", error = "") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = safeFramebaseError(error)
                mutableState.update { it.copy(error = message) }
                record("Recording failed", message, true)
            }
        }
    }

    /** Copies a bundled asset (or verifies an imported file) off the main thread before playback. */
    fun preparePlayback(clipId: String) {
        val clip = archive.clips.firstOrNull { it.id == clipId } ?: return
        val generation = ++playbackGeneration
        playbackJob?.cancel()
        mutableState.update { it.copy(playbackBusy = true, playbackError = "") }
        playbackJob = viewModelScope.launch(dispatcher) {
            try {
                val localClip = if (clip.bundled) {
                    store.ensureBundledLocal(clip)
                } else {
                    check(store.localFile(clip).isFile) { "Recording is no longer stored on this device" }
                    clip
                }
                check(store.localFile(localClip).isFile) { "Recording is no longer stored on this device" }
                if (disposed || generation != playbackGeneration) return@launch
                archive = archive.copy(clips = archive.clips.map { current ->
                    if (current.id == clip.id) localClip else current
                })
                saveArchive()
                mutableState.update { it.copy(playbackBusy = false, playbackError = "") }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!disposed && generation == playbackGeneration) {
                    mutableState.update {
                        it.copy(
                            playbackBusy = false,
                            playbackError = "The local video could not be read. Import it again.",
                        )
                    }
                }
            }
        }
    }

    fun prepare() {
        val active = gateway ?: return
        if (mutableState.value.busy || !mutableState.value.connected) return
        invalidateSearch()
        startPreparation(active, archive.pendingJobId.isNotBlank())
    }

    fun uploadAndIndex() = prepare()

    fun prepareVideos() = prepare()

    fun resumeIndex() {
        val active = gateway ?: return
        if (mutableState.value.busy || archive.pendingJobId.isBlank()) return
        invalidateSearch()
        startPreparation(active, true)
    }

    fun stopWork() {
        val wasBusy = mutableState.value.busy
        preparationGeneration++
        preparationJob?.cancel()
        preparationJob = null
        if (wasBusy) {
            val text = if (archive.pendingJobId.isBlank()) "Upload canceled. Completed uploads are kept." else "Stopped waiting. Indexing continues on the server; use Resume."
            mutableState.update { it.copy(busy = false, workPhase = FramebaseWorkPhase.IDLE, phase = "", progress = null, progressClipId = "", notice = text) }
            record("Operation stopped", text)
        }
    }

    fun cancelPreparation() = stopWork()

    fun stopWaiting() = stopWork()

    private fun startPreparation(active: FramebaseGateway, resumeOnly: Boolean) {
        val generation = ++preparationGeneration
        preparationJob?.cancel()
        preparationJob = viewModelScope.launch(dispatcher) {
            val jobAtStart = archive.pendingJobId
            mutableState.update {
                it.copy(
                    busy = true,
                    workPhase = if (resumeOnly) FramebaseWorkPhase.POLLING_INDEX else FramebaseWorkPhase.UPLOADING,
                    phase = if (resumeOnly) "Resuming visual index" else "Preparing videos",
                    progress = null,
                    progressClipId = "",
                    notice = "",
                    error = "",
                )
            }
            try {
                if (!resumeOnly) {
                    for (clip in archive.clips.filter { !it.uploaded }) {
                        ensureCurrentPreparation(generation)
                        val localClip = ensureLocalClip(clip)
                        val file = store.localFile(localClip)
                        val source = UploadSource.fromFile(file)
                        mutableState.update {
                            it.copy(workPhase = FramebaseWorkPhase.UPLOADING, phase = "Uploading ${clip.title}", progress = null, progressClipId = clip.id)
                        }
                        var completed = false
                        active.upload(source).collect { event ->
                            ensureCurrentPreparation(generation)
                            when (event) {
                                is VideoUploadEvent.Progress -> {
                                    val total = event.progress.totalBytes
                                    mutableState.update { it.copy(progress = if (total > 0) (event.progress.uploadedBytes.toDouble() / total).coerceIn(0.0, 1.0) else null) }
                                }
                                is VideoUploadEvent.Completed -> {
                                    if (!event.response.uploaded) error("upload did not complete")
                                    completed = true
                                }
                            }
                        }
                        if (!completed) error("upload did not complete")
                        archive = archive.copy(clips = archive.clips.map { if (it.id == clip.id) it.copy(uploaded = true) else it })
                        saveArchive()
                        record("Uploaded", "${clip.title} · ${formatMegabytes(file.length())} MB")
                    }
                } else if (jobAtStart.isBlank()) {
                    error("No pending index job is available")
                }
                ensureCurrentPreparation(generation)
                if (archive.pendingJobId.isBlank()) {
                    mutableState.update { it.copy(workPhase = FramebaseWorkPhase.CREATING_INDEX, phase = "Creating visual index", progress = null, progressClipId = "") }
                    val created = active.createIndex()
                    if (created.jobId.trim().isBlank()) error("index job id is missing")
                    archive = archive.copy(pendingJobId = created.jobId.trim())
                    saveArchive()
                    record("Index queued", "Visual index · ${archive.clips.size} recordings")
                }
                pollIndex(active, generation)
            } catch (error: CancellationException) {
                if (generation == preparationGeneration && !disposed) {
                    val text = if (archive.pendingJobId.isBlank()) "Upload canceled. Completed uploads are kept." else "Stopped waiting. Indexing continues on the server; use Resume."
                    mutableState.update { it.copy(notice = text) }
                    record("Operation stopped", text)
                }
                throw error
            } catch (error: Exception) {
                if (generation == preparationGeneration && !disposed) {
                    val text = safeFramebaseError(error)
                    mutableState.update { it.copy(error = text) }
                    record("Processing failed", text, true)
                }
            } finally {
                if (generation == preparationGeneration) {
                    mutableState.update { it.copy(busy = false, workPhase = FramebaseWorkPhase.IDLE, phase = "", progress = null, progressClipId = "") }
                }
            }
        }
    }

    private suspend fun pollIndex(active: FramebaseGateway, generation: Long) {
        val jobId = archive.pendingJobId
        val started = now()
        repeat(MAX_INDEX_POLLS) { attempt ->
            ensureCurrentPreparation(generation)
            val result = active.indexStatus(jobId)
            when {
                indexDone(result.status) -> {
                    val version = active.refreshIndexVersion()
                    archive = archive.copy(pendingJobId = "")
                    saveArchive()
                    mutableState.update {
                        it.copy(
                            connection = FramebaseConnectionState.SEARCH_READY,
                            indexVersion = version,
                            ready = version != null,
                            notice = "Visual index is ready. Search the street archive.",
                        )
                    }
                    record("Index ready", "${version?.let { "v$it" } ?: "Index ready"} · ${((now() - started).coerceAtLeast(0) / 1000.0).formatOneDecimal()} s waiting time")
                    return
                }
                indexFailed(result.status) -> {
                    archive = archive.copy(pendingJobId = "")
                    saveArchive()
                    error("Server index job failed")
                }
                else -> {
                    mutableState.update { it.copy(workPhase = FramebaseWorkPhase.POLLING_INDEX, phase = "Index ${result.status.ifBlank { "processing" }} · ${attempt * POLL_DELAY_MS / 1000}s", progress = null) }
                    wait(POLL_DELAY_MS)
                }
            }
        }
        mutableState.update { it.copy(notice = "Still processing. Use Resume to check the server job again.") }
    }

    fun setQuery(value: String) {
        if (value == mutableState.value.activeQuery) return
        invalidateSearch()
        mutableState.update { it.copy(activeQuery = value, notice = "", error = "") }
    }

    fun onQueryChanged(value: String) = setQuery(value)

    fun setMaxDistance(value: Double) {
        val clean = if (value <= FOCUSED_DISTANCE) FOCUSED_DISTANCE else LOOSE_DISTANCE
        if (clean == mutableState.value.maxDistance) return
        invalidateSearch()
        mutableState.update { it.copy(maxDistance = clean) }
    }

    fun setCutoff(value: Double) = setMaxDistance(value)

    fun setLooseMatches(enabled: Boolean) = setMaxDistance(if (enabled) LOOSE_DISTANCE else FOCUSED_DISTANCE)

    fun toggleLooseMatches(enabled: Boolean) = setLooseMatches(enabled)

    fun invalidateSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        mutableState.update { it.copy(searching = false, searchBatch = null) }
    }

    fun search(query: String = mutableState.value.activeQuery, maxDistance: Double = mutableState.value.maxDistance) {
        val active = gateway ?: return
        val current = mutableState.value
        if (current.busy || !current.ready || query.trim().isBlank()) return
        invalidateSearch()
        val generation = ++searchGeneration
        val cleanQuery = query.trim()
        val cutoff = if (maxDistance > FOCUSED_DISTANCE) LOOSE_DISTANCE else FOCUSED_DISTANCE
        mutableState.update { it.copy(activeQuery = cleanQuery, maxDistance = cutoff, searching = true, notice = "", error = "") }
        searchJob = viewModelScope.launch(dispatcher) {
            try {
                val result = active.search(cleanQuery, cutoff)
                if (disposed || generation != searchGeneration) return@launch
                mutableState.update { it.copy(searching = false, searchBatch = result) }
                record("Search complete", "Search returned ${result.matches.size} moments · ${result.roundTripMs} ms request")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == searchGeneration && !disposed) {
                    val text = safeFramebaseError(error)
                    mutableState.update { it.copy(searching = false, error = text) }
                    record("Search failed", text, true)
                }
            } finally {
                if (generation == searchGeneration) mutableState.update { it.copy(searching = false) }
            }
        }
    }

    fun clearSearch() = invalidateSearch()

    fun cancelSearch() = invalidateSearch()

    fun record(title: String, detail: String, isError: Boolean = false) {
        val clean = sanitizeDetail(detail)
        archive = archive.copy(events = prependEvent(archive.events, ArchiveEvent(title, clean, isError, isoTime(now()))))
        saveArchive()
        mutableState.update { it.copy(archive = archive) }
    }

    fun clearViewModel() {
        invalidateSearch()
        stopWork()
        playbackGeneration++
        playbackJob?.cancel()
        playbackJob = null
        archive = ArchiveState()
        mutableState.value = FramebaseUiState(initialized = true, initializing = false, archive = archive, connection = FramebaseConnectionState.DISCONNECTED)
    }

    private fun ensureLocalClip(clip: ArchiveClip): ArchiveClip {
        if (!clip.bundled || !clip.localPath.isNullOrBlank()) return clip
        val updated = store.ensureBundledLocal(clip)
        archive = archive.copy(clips = archive.clips.map { if (it.id == clip.id) updated else it })
        saveArchive()
        return updated
    }

    private fun ensureCurrentPreparation(generation: Long) {
        if (disposed || generation != preparationGeneration) throw CancellationException("stale preparation")
    }

    private fun saveArchive() {
        // Persistence is optional state. A failure must never cancel active network work.
        runCatching { store.save(archive) }
        mutableState.update { it.copy(archive = archive) }
    }

    private fun publish(change: FramebaseUiState.() -> FramebaseUiState) {
        if (!disposed) mutableState.update(change)
    }

    override fun onCleared() {
        disposed = true
        searchGeneration++
        preparationGeneration++
        searchJob?.cancel()
        preparationJob?.cancel()
        playbackGeneration++
        playbackJob?.cancel()
        val old = gateway
        gateway = null
        runBlocking(NonCancellable) { old?.close() }
        super.onCleared()
    }

    companion object {
        private const val MAX_INDEX_POLLS = 120
        private const val POLL_DELAY_MS = 4_000L

        fun factory(store: ArchiveStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = FramebaseViewModel(store) as T
            }
    }
}

/** Account-safe, bounded UI error conversion. Raw response bodies and submitted keys never pass through. */
fun safeFramebaseError(error: Throwable): String = when (error) {
    is AuthError -> "Authentication failed. Check your API key and beta access."
    is FileNotFoundException -> "The local video could not be read. Import it again."
    is IllegalArgumentException -> {
        val text = error.message.orEmpty()
        if (text.startsWith("Choose a playable MP4")) "Choose a playable MP4 smaller than 100 MB."
        else "The operation could not finish. Check the connection and try again."
    }
    is ApiError, is SdkError -> "The operation could not finish. Check the connection and try again."
    else -> "The operation could not finish. Check the connection and try again."
}

private fun sanitizeDetail(value: String): String {
    val clean = value.replace(Regex("(?i)bearer\\s+[^\\s,;]+"), "Bearer [REDACTED]")
        .replace(Regex("(?i)(api[_ -]?key|authorization|token|secret)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
        .replace(Regex("https?://[^\\s]+"), "[URL]")
        .replace(Regex("(?:[A-Za-z]:[\\\\/]|/)[^\\s]+"), "[PATH]")
        .replace(Regex("\\s+"), " ")
        .trim()
    return clean.take(240)
}

private fun formatMegabytes(bytes: Long): String = "%.1f".format(Locale.US, bytes / 1_048_576.0)

private fun Double.formatOneDecimal(): String = "%.1f".format(Locale.US, this)

private fun isoTime(value: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(value))

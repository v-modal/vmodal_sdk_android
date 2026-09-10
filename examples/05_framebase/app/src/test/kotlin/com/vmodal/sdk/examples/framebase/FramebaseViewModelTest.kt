package com.vmodal.sdk.examples.framebase

import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadEvent
import com.vmodal.sdk.AuthError
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramebaseViewModelTest {
    @Test
    fun connectPublishesOnlySafeAccountStateAndResetsOnAccountChange() {
        val store = FakeFramebaseStore(ArchiveState(
            clips = streetClips().map { it.copy(uploaded = true) },
            pendingJobId = "job-1",
            accountId = "old-account",
            events = listOf(ArchiveEvent("old", "safe")),
        ))
        val gateway = FakeFramebaseGateway("new-account", 7)
        val viewModel = FramebaseViewModel(store, { gateway }, Dispatchers.Unconfined, { })

        viewModel.connect("secret-key-must-not-be-persisted")

        assertEquals("new-account", viewModel.state.value.accountId)
        assertEquals(7, viewModel.state.value.indexVersion)
        assertTrue(viewModel.state.value.clips.none { it.uploaded })
        assertEquals("", viewModel.state.value.pendingJobId)
        assertTrue(viewModel.state.value.events.any { it.title == "Connected" })
        assertFalse(store.savedText.contains("secret-key"))
        assertFalse(viewModel.state.value.events.any { it.detail.contains("secret-key") })
    }

    @Test
    fun blankPersistedAccountStillResetsStaleOwnedArchiveState() {
        val store = FakeFramebaseStore(ArchiveState(
            clips = streetClips().map { it.copy(uploaded = true) },
            pendingJobId = "stale-job",
            accountId = "",
            events = listOf(ArchiveEvent("stale", "old account work")),
        ))
        val gateway = FakeFramebaseGateway("new-account", 7)
        val viewModel = FramebaseViewModel(store, gateway, Dispatchers.Unconfined, { })

        viewModel.connect("key")

        assertEquals("new-account", viewModel.state.value.accountId)
        assertTrue(viewModel.state.value.clips.none { it.uploaded })
        assertEquals("", viewModel.state.value.pendingJobId)
        assertFalse(viewModel.state.value.events.any { it.title == "stale" })
        assertEquals(1, store.resetSaveCount)
    }

    @Test
    fun failedCredentialReplacementRestoresThePreviousConnectionState() {
        val oldGateway = FakeFramebaseGateway("old-account", 9)
        val failedGateway = FailingFramebaseGateway()
        val gateways = ArrayDeque<FramebaseGateway>(listOf(oldGateway, failedGateway))
        val viewModel = FramebaseViewModel(FakeFramebaseStore(), { gateways.removeFirst() }, Dispatchers.Unconfined, { })

        viewModel.connect("old-key")
        viewModel.connect("replacement-key")

        assertEquals(FramebaseConnectionState.SEARCH_READY, viewModel.state.value.connection)
        assertTrue(viewModel.state.value.connected)
        assertEquals("old-account", viewModel.state.value.accountId)
        assertEquals(9, viewModel.state.value.indexVersion)
        assertTrue(viewModel.state.value.ready)
        assertFalse(oldGateway.closed)
        assertFalse(viewModel.state.value.error.isBlank())
    }

    @Test
    fun queryAndCutoffInvalidateExistingBatch() {
        val gateway = FakeFramebaseGateway("account", 4)
        val viewModel = FramebaseViewModel(FakeFramebaseStore(), { gateway }, Dispatchers.Unconfined, { })
        viewModel.connect("key")
        viewModel.search("street")
        assertEquals(1, viewModel.state.value.searchBatch?.matches?.size)

        viewModel.setQuery("bus")
        assertNull(viewModel.state.value.searchBatch)
        viewModel.search("bus")
        assertEquals(1, viewModel.state.value.searchBatch?.matches?.size)
        viewModel.setLooseMatches(true)
        assertNull(viewModel.state.value.searchBatch)
        assertEquals(LOOSE_DISTANCE, viewModel.state.value.maxDistance, 0.0)
    }

    @Test
    fun errorsAreBoundedAndCredentialFree() {
        val message = safeFramebaseError(IllegalArgumentException(
            "Bearer secret https://example.test/a?token=secret /private/absolute/file.mp4",
        ))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("example.test"))
        assertFalse(message.contains("/private"))
        assertTrue(message.length <= 240)
    }

    @Test
    fun playbackCopiesBundledClipAndPublishesLocalPath() {
        val store = FakeFramebaseStore()
        val viewModel = FramebaseViewModel(store, { FakeFramebaseGateway("account", 1) }, Dispatchers.Unconfined, { })

        viewModel.preparePlayback(streetClips().first().id)

        assertEquals(1, store.ensureBundledCalls)
        assertFalse(viewModel.state.value.playbackBusy)
        assertTrue(viewModel.state.value.clips.first().localPath?.isNotBlank() == true)
        assertEquals("", viewModel.state.value.playbackError)
    }

    @Test
    fun playbackReadFailureUsesExactLocalVideoMessage() {
        val clip = newImportedClip("street_1", "Imported", 2.0, "")
        val store = FakeFramebaseStore(ArchiveState(clips = listOf(clip)))
        val viewModel = FramebaseViewModel(store, { FakeFramebaseGateway("account", 1) }, Dispatchers.Unconfined, { })

        viewModel.preparePlayback(clip.id)

        assertFalse(viewModel.state.value.playbackBusy)
        assertEquals("The local video could not be read. Import it again.", viewModel.state.value.playbackError)
    }
}

private class FakeFramebaseStore(initial: ArchiveState = ArchiveState()) : FramebaseStore {
    private var value = initial
    var savedText = ""
    var ensureBundledCalls = 0
    var resetSaveCount = 0

    override fun load(): ArchiveState = value

    override fun save(state: ArchiveState): Boolean {
        if (value.accountId != state.accountId && state.accountId.isNotBlank()) resetSaveCount++
        value = state
        savedText = state.events.joinToString("|") { "${it.title}:${it.detail}" }
        return true
    }

    override fun localFile(clip: ArchiveClip): File = File(clip.localPath ?: error("missing local file"))

    override fun ensureBundledLocal(clip: ArchiveClip): ArchiveClip {
        ensureBundledCalls++
        return clip.copy(localPath = File.createTempFile(clip.id, ".mp4").absolutePath)
    }

    override fun importVideo(uri: android.net.Uri, displayName: String?): ArchiveClip =
        error("not used in this test")
}

private class FakeFramebaseGateway(
    override val accountId: String,
    override val indexVersion: Int?,
) : FramebaseGateway {
    var closed = false

    override suspend fun connect(): FramebaseConnection = FramebaseConnection(accountId, indexVersion)
    override fun upload(source: UploadSource): Flow<VideoUploadEvent> = emptyFlow()
    override suspend fun createIndex(): FramebaseIndexResult = FramebaseIndexResult("job", "queued")
    override suspend fun indexStatus(jobId: String): FramebaseIndexResult = FramebaseIndexResult(jobId, "done")
    override suspend fun search(query: String, maxDistance: Double): SearchBatch = SearchBatch(
        matches = listOf(FrameMatch(mapOf("score" to .5), "street.mp4")),
        total = 1,
        serverMs = 1.0,
        roundTripMs = 2,
        imageMs = 0,
    )
    override suspend fun close() {
        closed = true
    }
}

private class FailingFramebaseGateway : FramebaseGateway {
    override val accountId: String = ""
    override val indexVersion: Int? = null
    override suspend fun connect(): FramebaseConnection = throw AuthError("replacement rejected")
    override fun upload(source: UploadSource): Flow<VideoUploadEvent> = emptyFlow()
    override suspend fun createIndex(): FramebaseIndexResult = error("not used")
    override suspend fun indexStatus(jobId: String): FramebaseIndexResult = error("not used")
    override suspend fun search(query: String, maxDistance: Double): SearchBatch = error("not used")
    override suspend fun close() = Unit
}

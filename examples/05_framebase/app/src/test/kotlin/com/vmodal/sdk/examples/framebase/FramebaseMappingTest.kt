package com.vmodal.sdk.examples.framebase

import com.vmodal.sdk.Client
import com.vmodal.sdk.AuthError
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.VmodalRequest
import com.vmodal.sdk.VmodalResponse
import com.vmodal.sdk.VmodalTransport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FramebaseMappingTest {
    @Test
    fun connectSelectsGreatestFixedCollectionVersionAndSearchUsesExactScope() = runBlocking {
        val transport = MappingTransport()
        val keys = MutableApiKeyProvider("test-key")
        val client = Client(baseUrl = "https://fake.test", transport = transport, apiKeyProvider = keys)
        val gateway = SdkFramebaseGateway(keys, client)

        val connection = gateway.connect()
        assertEquals("account-1", connection.accountId)
        assertEquals(12, connection.indexVersion)
        val batch = gateway.search("people crossing", FOCUSED_DISTANCE)
        assertEquals(2, batch.matches.size)
        assertEquals(2, batch.total)
        assertEquals(byteArrayOf(1, 2, 3).toList(), batch.matches[0].imageBytes?.toList())

        val request = transport.requests.first { it.path.endsWith("/search") }
        val body = request.jsonBody as Map<*, *>
        assertEquals(FRAMEBASE_MODE, body["mode"])
        assertEquals(ARCHIVE_COLLECTION, body["group_name"])
        assertEquals(ARCHIVE_STREAM, body["stream_name"])
        assertEquals(30, body["limit"])
        assertEquals(FOCUSED_DISTANCE, body["image_emb_score_min"])
        assertEquals(12, body["version_lancedb"])
        assertEquals(listOf("image"), body["search_sources"])
        val created = gateway.createIndex()
        assertEquals("job-1", created.jobId)
        assertEquals("queued", created.status)
        val createRequest = transport.requests.first { it.path.contains("/indexation/job/create") }
        val createBody = createRequest.jsonBody as Map<*, *>
        assertEquals(FRAMEBASE_MODE, createBody["mode"])
        assertEquals(ARCHIVE_COLLECTION, createBody["group_name"])
        assertEquals(ARCHIVE_STREAM, createBody["stream_name"])
        assertEquals(true, createBody["re_process"])
        assertEquals("vid_img_emb", createBody["index_type"])
        assertEquals("vid_img_emb", createBody["modality"])
        assertEquals("done", gateway.indexStatus("job-1").status)
        gateway.close()
        assertFailsWithAuth(keys)
    }

    @Test
    fun aliasesItemIdSeparatorsAndPlaybackTimesRemainDeterministic() {
        val aliases = listOf("filename", "filename_sanitized", "video_filename", "video", "source_path", "path", "title")
        aliases.forEach { field ->
            assertEquals("street.mp4", gatewayHitFilename(mapOf(field to "a\\nested/street.mp4")))
        }
        assertEquals("No file", gatewayHitFilename(mapOf("filename" to " / ", "title" to "No file")))
        assertEquals(
            "street",
            gatewayHitFilename(
                mapOf(
                    "stream" to "street_study",
                    "item_id" to "street_study-street-0000000002000",
                    "ts_unix" to "0000000002000",
                ),
            ),
        )
        assertEquals("1700000000000", gatewayTimestamp13(mapOf("ts_unix" to "1700000000")))
        assertEquals("0000000002000", gatewayTimestamp13(mapOf("timestamp_ms" to 2000)))
        assertEquals(2.0, gatewayHitSeconds(mapOf("ts_unix" to "0000000002000")))
        assertNull(gatewayHitSeconds(mapOf("ts_unix" to "1788510000000")))
        assertEquals(4.5, gatewayHitSeconds(mapOf("video_time_seconds" to "4.5", "ts_unix" to 1_788_510_000_000L)))
    }

    @Test
    fun candidateCutoffKeepsBackendCountIndependentAndSkipsEmptyImageCalls() {
        val rows = listOf(
            mapOf<String, Any?>("filename" to "keep.mp4", "score" to 0.84),
            mapOf<String, Any?>("filename" to "drop.mp4", "score" to 0.86),
            mapOf<String, Any?>("filename" to "nan.mp4", "score" to Double.NaN),
        )
        val candidates = framebaseCandidates(rows, FOCUSED_DISTANCE)
        assertEquals(1, candidates.size)
        assertEquals("keep.mp4", candidates.single().record["filename"])
        assertTrue(framebaseCandidates(emptyList(), FOCUSED_DISTANCE).isEmpty())
        assertTrue(gatewayValidImageLocator("/api/external/v1/image/get_image"))
        assertTrue(gatewayValidImageLocator("https://cdn.test/image"))
        assertFalse(gatewayValidImageLocator("/api/external/v1/image/get_image/other"))
        assertFalse(gatewayValidImageLocator("http://cdn.test/image"))
    }

    @Test
    fun bulkJoinPreservesRankRejectsBadIndexesDuplicatesAndBadBase64() {
        val candidates = framebaseCandidates(
            listOf(
                mapOf<String, Any?>("filename" to "zero.mp4", "score" to .1),
                mapOf<String, Any?>("filename" to "one.mp4", "score" to .2),
                mapOf<String, Any?>("filename" to "two.mp4", "score" to .3),
            ),
            LOOSE_DISTANCE,
        )
        val urls = resolveImageUrls(
            listOf(
                mapOf("input_index" to "2", "url_pre_signed" to "https://cdn.test/two"),
                mapOf("input_index" to 0.0, "url_pre_signed" to "/api/external/v1/image/get_image"),
                mapOf("input_index" to "2", "url_pre_signed" to "https://cdn.test/duplicate"),
                mapOf("input_index" to -1, "url_pre_signed" to "https://cdn.test/negative"),
                mapOf("input_index" to "bad", "url_pre_signed" to "https://cdn.test/bad"),
                mapOf("input_index" to 1, "found" to false, "url_pre_signed" to "https://cdn.test/missing"),
            ),
            candidates.size,
        )
        assertEquals(listOf(2, 0), urls.keys.toList())
        val bytes = resolveImageBytes(
            listOf(
                mapOf("input_index" to 1, "url_pre_signed" to "https://cdn.test/two", "content_base64" to "aGk="),
                mapOf("input_index" to 0, "url_pre_signed" to "/api/external/v1/image/get_image", "content_base64" to "not-base64"),
            ),
            urls,
            listOf(0, 2),
        )
        assertTrue(bytes[2]!!.contentEquals(byteArrayOf(104, 105)))
        assertNull(bytes[0])
        val matches = candidates.mapIndexed { index, candidate ->
            FrameMatch(candidate.row, candidate.filename, candidate.timestamp, candidate.seconds, bytes[index], urls[index])
        }
        assertEquals(listOf("zero.mp4", "one.mp4", "two.mp4"), matches.map(FrameMatch::filename))
        assertNull(matches[1].imageBytes)
    }

    @Test
    fun terminalStateAliasesAndCredentialBoundaryAreCovered() {
        assertTrue(indexDone("Succeeded"))
        assertTrue(indexDone("OK"))
        assertTrue(indexFailed("CANCELED"))
        assertFalse(indexFailed("queued"))
    }

    @Test
    fun urlLookupFailureKeepsAcceptedRankedRowsAsPlaceholders() = runBlocking {
        val transport = MappingTransport(failUrlLookup = true)
        val keys = MutableApiKeyProvider("test-key")
        val gateway = SdkFramebaseGateway(
            keys,
            Client(baseUrl = "https://fake.test", transport = transport, apiKeyProvider = keys),
        )
        gateway.connect()
        val batch = gateway.search("people crossing", FOCUSED_DISTANCE)
        assertEquals(2, batch.matches.size)
        assertTrue(batch.matches.all { it.imageBytes == null })
        gateway.close()
    }

    private fun assertFailsWithAuth(provider: MutableApiKeyProvider) {
        assertFailsWith<AuthError> { provider.current() }
    }
}

private class MappingTransport(
    private val failUrlLookup: Boolean = false,
) : VmodalTransport {
    val requests = mutableListOf<VmodalRequest>()

    override fun execute(request: VmodalRequest): VmodalResponse {
        requests += request
        if (failUrlLookup && request.path.contains("/image/get_url_bulk")) {
            error("fake URL lookup failure")
        }
        val body = when {
            request.path.contains("/auth/me") -> "{\"user_id\":\"account-1\"}"
            request.path.contains("/collection/groups") ->
                "{\"data\":[{" +
                    "\"mode\":\"vid_file\",\"group_name\":\"framebase_streets\",\"lancedb_versions\":[\"v2\",\"v12\"]" +
                    "},{\"mode\":\"vid_file\",\"group_name\":\"other\",\"lancedb_versions\":[\"v99\"]}]}"
            request.path.endsWith("/search") ->
                "{\"data\":[" +
                    "{\"filename\":\"zero.mp4\",\"score\":0.2,\"ts_unix\":\"0000000002000\"}," +
                    "{\"filename\":\"over.mp4\",\"score\":0.9}," +
                    "{\"filename\":\"one.mp4\",\"score\":0.4}],\"cnt_total\":2,\"execution_time_ms\":7.5}"
            request.path.contains("/indexation/job/create") ->
                "{\"job_id\":\"job-1\",\"status\":\"queued\"}"
            request.path.contains("/indexation/job/job-1") ->
                "{\"job_id\":\"job-1\",\"status\":\"done\"}"
            request.path.contains("/image/get_url_bulk") ->
                "{\"records\":[" +
                    "{\"input_index\":1,\"found\":true,\"url_pre_signed\":\"https://cdn.test/one\"}," +
                    "{\"input_index\":0,\"found\":true,\"url_pre_signed\":\"https://cdn.test/zero\"}]}"
            request.path.contains("/image/get_image_bulk") ->
                "{\"records\":[{\"url_pre_signed\":\"https://cdn.test/zero\",\"content_base64\":\"AQID\"}]}"
            else -> "{}"
        }
        return VmodalResponse(200, body)
    }
}

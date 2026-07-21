package com.vmodal.sdk.examples.fullapp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullAppSearchMappingTest {
    @Test
    fun filenameAliasesAndLookupRecordsAreNormalized() {
        val aliases = listOf(
            "filename",
            "filename_sanitized",
            "video_filename",
            "video",
            "source_path",
            "path",
        )
        aliases.forEach { name ->
            val candidates = searchCandidates(
                listOf(mapOf(name to " folder\\nested/video.mp4 ")),
                " videos ",
                " fallback ",
            )
            assertEquals(
                mapOf(
                    "mode" to "vid_file",
                    "group_name" to "videos",
                    "modality" to "vid_img",
                    "stream_name" to "fallback",
                    "filename" to "video.mp4",
                ),
                candidates.single().record,
            )
        }
        val omitted = searchCandidates(
            listOf(mapOf("filename" to " / "), mapOf("title" to "No file")),
            "group",
            "stream",
        )
        assertTrue(omitted.isEmpty())
        val timed = searchCandidates(
            listOf(
                mapOf(
                    "source_path" to "folder/clip.mp4",
                    "stream_name" to " custom ",
                    "ts_unix" to "1700000000",
                ),
            ),
            " videos ",
            " fallback ",
        ).single()
        assertEquals(
            mapOf(
                "mode" to "vid_file",
                "group_name" to "videos",
                "modality" to "vid_img",
                "stream_name" to "custom",
                "filename" to "clip.mp4",
                "ts_unix_13digits" to "1700000000000",
            ),
            timed.record,
        )
    }

    @Test
    fun liveSearchFieldsProduceAnImageLookupRecord() {
        val candidate = searchCandidates(
            listOf(
                mapOf(
                    "stream" to "astream",
                    "item_id" to "astream-video_10frames-0000000002000",
                    "title" to "video_10frames",
                    "ts_unix" to "0000000002000",
                ),
            ),
            "videos",
            "fallback",
        ).single()
        assertEquals(
            mapOf(
                "mode" to "vid_file",
                "group_name" to "videos",
                "modality" to "vid_img",
                "stream_name" to "astream",
                "filename" to "video_10frames",
                "ts_unix_13digits" to "0000000002000",
            ),
            candidate.record,
        )
    }

    @Test
    fun timestampsAndScoresFollowTheCrossSdkContract() {
        assertEquals("1700000000000", strTimestamp13("1700000000"))
        assertEquals("1700000000123", strTimestamp13("1700000000123"))
        assertEquals("1700000000123", strTimestamp13("170000000012345"))
        assertEquals("0000000002000", strTimestamp13("2000"))
        assertEquals("0000000002000", strTimestamp13("time=2,000ms"))
        assertEquals("", strTimestamp13(""))
        assertEquals("87.5%", strSearchScore(mapOf("score_ui" to 0.875)))
        assertEquals("0.42", strSearchScore(mapOf("score_ui" to Double.NaN, "score" to 0.42)))
        assertEquals("", strSearchScore(mapOf("score_ui" to Double.POSITIVE_INFINITY)))
    }

    @Test
    fun bulkJoinValidatesIndexesDuplicatesAndPartialFailures() {
        val rows = List(4) { index ->
            mapOf<String, Any?>(
                "filename" to "video_$index.mp4",
                "title" to "Title $index",
            )
        }
        val candidates = searchCandidates(rows, "group", "stream")
        val images = searchImages(
            candidates,
            listOf(
                mapOf("input_index" to "2", "url_pre_signed" to "https://image.test/2"),
                mapOf("input_index" to 0.0, "url_pre_signed" to "https://image.test/0"),
                mapOf("input_index" to "2", "url_pre_signed" to "https://image.test/duplicate"),
                mapOf("input_index" to -1, "url_pre_signed" to "https://image.test/negative"),
                mapOf("input_index" to 9, "url_pre_signed" to "https://image.test/large"),
                mapOf("input_index" to "bad", "url_pre_signed" to "https://image.test/bad"),
                mapOf("input_index" to 1, "found" to false, "url_pre_signed" to "https://image.test/missing"),
                mapOf("input_index" to 3, "url_pre_signed" to " "),
            ),
        )
        assertEquals(listOf("Title 0", "Title 2"), images.map(SearchImage::title))
        assertEquals("https://image.test/2", images.last().url)
    }

    @Test
    fun missingIndexesUseOnlyBoundedPositionAndKeepOriginalRanks() {
        val candidates = searchCandidates(
            listOf(
                mapOf("title" to "Filtered"),
                mapOf("filename" to "same.mp4", "effective_title" to "First"),
                mapOf("filename" to "same.mp4", "caption" to "Second"),
            ),
            "group",
            "stream",
        )
        val images = searchImages(
            candidates,
            listOf(
                mapOf("url_pre_signed" to "https://image.test/first"),
                mapOf("url_pre_signed" to "https://image.test/second"),
                mapOf("url_pre_signed" to "https://image.test/outside"),
            ),
        )
        assertEquals(listOf("First", "Second"), images.map(SearchImage::title))
        assertTrue(images[0].id.startsWith("1-same.mp4-"))
        assertTrue(images[1].id.startsWith("2-same.mp4-"))
        images.forEach { image -> assertFalse(image.id.contains(image.url)) }
    }

    @Test
    fun everyReturnedHitIsEligibleAndCountsStayIndependent() {
        val rows = List(12) { index -> mapOf("filename" to "video_$index.mp4") }
        val candidates = searchCandidates(rows, "group", "stream")
        assertEquals(12, candidates.size)
        val output = searchOutput(
            candidates = candidates,
            records = listOf(mapOf("input_index" to 4, "url_pre_signed" to "https://image.test/4")),
            total = 91,
            returned = 12,
            elapsedMs = 146.5,
        )
        assertEquals(1, output.images.size)
        assertEquals(91, output.total)
        assertEquals(12, output.returned)
        assertEquals(146.5, output.elapsedMs)
    }

    @Test
    fun imageFailuresExposeOnlyClassAndHttpStatus() {
        val secret = "https://image.test/frame.jpg?url_hash_jwt=credential"
        val error = IllegalStateException(secret, RuntimeException("HTTP status 403 for $secret"))
        val message = strImageLoadFailure(error)
        assertEquals("Image load failed: cause=IllegalStateException -> RuntimeException status=403", message)
        assertFalse(secret in message)
        assertFalse("credential" in message)
    }

    @Test
    fun imageContentBulkJoinDecodesAndPreservesFailedCards() {
        val images = listOf(
            SearchImage("0", "relative-0", "zero", "0.jpg", "s", "", ""),
            SearchImage("1", "relative-1", "one", "1.jpg", "s", "", ""),
        )
        val values = searchImageBytes(
            images,
            listOf(
                mapOf("input_index" to 1, "found" to true, "content_base64" to "AQID"),
                mapOf("input_index" to 0, "found" to false, "content_base64" to "BAUG"),
            ),
        )
        assertTrue(values[0].bytes.isEmpty())
        assertTrue(values[1].bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf("zero", "one"), values.map(SearchImage::title))
    }
}

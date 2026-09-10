package com.vmodal.sdk.examples.framebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramebaseArchiveTest {
    @Test
    fun bundledRecordsMatchFlutterReference() {
        val clips = streetClips()
        assertEquals(listOf("neighborhood_crossing", "downtown_traffic", "evening_junction"), clips.map { it.id })
        assertEquals(listOf(55.2, 15.8, 75.0), clips.map { it.durationSec })
        assertEquals("San Francisco", clips[0].city)
        assertEquals("videos/neighborhood_crossing.mp4", clips[0].assetPath)
        assertEquals("neighborhood_crossing.mp4", clips[0].remoteFilename)
        assertTrue(clips.all { it.bundled && it.localPath == null })
    }

    @Test
    fun filenamesNormalizeAliasesAndIds() {
        assertEquals("clip.mp4", hitFilename(mapOf("source_path" to "a\\b/clip.mp4")))
        assertEquals("clip", hitFilename(mapOf("title" to "clip")))
        assertEquals(
            "clip",
            hitFilename(mapOf("stream_name" to "street_study", "item_id" to "street_study-clip-0000000006000", "ts_unix" to "0000000006000")),
        )
        val clip = streetClips().first()
        assertTrue(filenameMatches(clip, "dir/NEIGHBORHOOD_CROSSING.MP4"))
        assertTrue(filenameMatches(clip, "neighborhood_crossing.thumbnail"))
        assertNull(clipFor(streetClips(), "other.mp4"))
    }

    @Test
    fun timestampsAndPlaybackRejectBadEpochValues() {
        assertEquals("0000000006000", timestamp13(mapOf("ts_unix" to "6000")))
        assertEquals("0000006000000", timestamp13(mapOf("ts_unix" to "6000000")))
        assertEquals("1788510000000", timestamp13(mapOf("ts_unix" to "1788510000000")))
        assertEquals("0000000035000", timestamp13(mapOf("ts_unix" to "0000000035000")))
        assertEquals(35.0, hitSeconds(mapOf("ts_unix" to "0000000035000"))!!, 0.0001)
        assertNull(hitSeconds(mapOf("ts_unix" to "1788510000000")))
        assertNull(hitSeconds(mapOf("ts_unix" to "-5")))
        assertEquals(12.5, hitSeconds(mapOf("video_time_seconds" to "12.5"))!!, 0.0001)
    }

    @Test
    fun nearbyMomentsKeepRelevanceAndVideoOrder() {
        fun hit(name: String, seconds: Double?) = FrameMatch(mapOf<String, Any?>("score" to .7), name, seconds = seconds)
        val grouped = groupMoments(
            listOf(hit("one", 35.0), hit("one", 36.0), hit("two", 35.0), hit("one", 22.0), hit("one", null)),
        )
        assertEquals(listOf("one", "two"), grouped.keys.toList())
        assertEquals(listOf(35.0, 22.0, null), grouped["one"]!!.map { it.seconds })
        assertEquals(1, grouped["two"]!!.size)
    }

    @Test
    fun eventBoundAndStateResetAreDeterministic() {
        var events = emptyList<ArchiveEvent>()
        repeat(45) { events = prependEvent(events, ArchiveEvent("e$it", "detail")) }
        assertEquals(MAX_ARCHIVE_EVENTS, events.size)
        assertEquals("e44", events.first().title)
        assertEquals("e5", events.last().title)

        val state = ArchiveState(
            clips = streetClips().map { it.copy(uploaded = true) },
            pendingJobId = "job-1",
            accountId = "old",
            events = events,
        )
        val reset = resetForAccount(state, "new")
        assertEquals("new", reset.accountId)
        assertTrue(reset.clips.none { it.uploaded })
        assertEquals("", reset.pendingJobId)
        assertTrue(reset.events.isEmpty())
        assertFalse(resetForAccount(state, "old").events.isEmpty())
    }

    @Test
    fun durationFormattingCoversZeroFractionNonFiniteAndLargeValues() {
        assertEquals("00:00", timeLabel(0.0))
        assertEquals("00:55", timeLabel(55.2))
        assertEquals("01:15", timeLabel(75.0))
        assertEquals("00:00", timeLabel(Double.NaN))
        assertEquals("00:00", timeLabel(Double.POSITIVE_INFINITY))
        assertEquals("1440:00", durationLabel(999999.0))
    }

    @Test
    fun importedClipOwnsOnlyPrivatePathMetadata() {
        val clip = newImportedClip("street_123", "outside.mp4", 3.5, "/app/files/framebase/media/street_123.mp4")
        assertEquals("street_123", clip.id)
        assertEquals("outside.mp4", clip.title)
        assertEquals("Imported recording", clip.location)
        assertEquals(false, clip.bundled)
        assertEquals(false, clip.uploaded)
        assertEquals("/app/files/framebase/media/street_123.mp4", clip.localPath)
        assertEquals(null, clip.assetPath)
    }
}

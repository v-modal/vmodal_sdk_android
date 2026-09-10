package com.vmodal.sdk.examples.framebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePlaybackPlayer(
    override var snapshot: PlaybackSnapshot,
) : PlaybackPlayer {
    var preparedPath = ""
    val seeks = mutableListOf<Long>()
    var plays = 0
    var pauses = 0
    var releases = 0

    override fun addListener(listener: () -> Unit) = Unit
    override fun removeListener(listener: () -> Unit) = Unit
    override fun prepare(localPath: String) { preparedPath = localPath }
    override fun play() { plays++ }
    override fun pause() { pauses++ }
    override fun seekTo(positionMs: Long) { seeks += positionMs }
    override fun release() { releases++ }
}

class PlaybackSheetTest {
    @Test
    fun test1_safeInitialSeekAcceptsOnlyFiniteInDuration() {
        assertEquals(12_500L, safeInitialSeekMs(12.5, 20_000L))
        assertEquals(20_000L, safeInitialSeekMs(20.0, 20_000L))
        assertNull(safeInitialSeekMs(20.01, 20_000L))
        assertNull(safeInitialSeekMs(-1.0, 20_000L))
        assertNull(safeInitialSeekMs(Double.NaN, 20_000L))
        assertNull(safeInitialSeekMs(2.0, 0L))
    }

    @Test
    fun test2_epochLikeOrOutOfRangePositionDoesNotSeek() {
        val player = FakePlaybackPlayer(PlaybackSnapshot(PlaybackStatus.READY, durationMs = 15_000L))
        val session = PlaybackSession(player, 1_700_000_000.0)
        session.onReady()
        session.seekToMoment(15.001)
        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun test3_initialSeekIsAppliedOnceWhenReady() {
        val player = FakePlaybackPlayer(PlaybackSnapshot(PlaybackStatus.READY, durationMs = 55_200L))
        val session = PlaybackSession(player, 4.2)
        session.initialize("/private/neighborhood.mp4")
        session.onReady()
        session.onReady()
        assertEquals("/private/neighborhood.mp4", player.preparedPath)
        assertEquals(listOf(4_200L), player.seeks)
    }

    @Test
    fun test4_controlsUseSamePlayerAndClampSlider() {
        val player = FakePlaybackPlayer(PlaybackSnapshot(PlaybackStatus.PAUSED, durationMs = 15_000L))
        val session = PlaybackSession(player)
        session.togglePlayback()
        session.seekToSlider(17_000L)
        player.snapshot = player.snapshot.copy(status = PlaybackStatus.PLAYING)
        session.togglePlayback()
        assertEquals(1, player.plays)
        assertEquals(1, player.pauses)
        assertEquals(listOf(15_000L), player.seeks)
    }

    @Test
    fun test5_activeMomentUsesStrictThreeSecondWindow() {
        assertTrue(playbackMomentActive(10_000L, 12.9))
        assertFalse(playbackMomentActive(10_000L, 13.0))
        assertFalse(playbackMomentActive(10_000L, null))
        assertFalse(playbackMomentActive(10_000L, Double.POSITIVE_INFINITY))
    }

    @Test
    fun test6_releaseStopsFurtherSessionWork() {
        val player = FakePlaybackPlayer(PlaybackSnapshot(PlaybackStatus.PAUSED, durationMs = 20_000L))
        val session = PlaybackSession(player)
        session.release()
        assertEquals(1, player.releases)
        assertEquals("00:01", playbackTimeLabel(1_999L))
        assertEquals("01:15", playbackTimeLabel(75_000L))
    }
}

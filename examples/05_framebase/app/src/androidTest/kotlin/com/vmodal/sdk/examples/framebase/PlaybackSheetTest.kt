package com.vmodal.sdk.examples.framebase

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private class ComposeFakePlayer(
    override var snapshot: PlaybackSnapshot,
) : PlaybackPlayer {
    val seeks = mutableListOf<Long>()
    var released = false
    override fun addListener(listener: () -> Unit) = Unit
    override fun removeListener(listener: () -> Unit) = Unit
    override fun prepare(localPath: String) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) { seeks += positionMs }
    override fun release() { released = true }
}

@RunWith(AndroidJUnit4::class)
class PlaybackSheetComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun test1_sheetKeepsHeaderAndControlsInLoadingState() {
        val player = ComposeFakePlayer(PlaybackSnapshot())
        composeRule.setContent {
            PlaybackSheet(
                clip = streetClips().first(),
                onClose = {},
                playerFactory = PlaybackPlayerFactory { player },
            )
        }
        composeRule.onNodeWithTag(PLAYBACK_CLOSE_TAG).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun test2_otherMomentTapUsesInjectedPlayerAndDisposeReleases() {
        val player = ComposeFakePlayer(PlaybackSnapshot(PlaybackStatus.READY, durationMs = 55_200L))
        val moments = listOf(
            FrameMatch(emptyMap(), "neighborhood_crossing.mp4", seconds = 4.0),
            FrameMatch(emptyMap(), "neighborhood_crossing.mp4", seconds = 12.0),
        )
        var showSheet = true
        composeRule.setContent {
            if (showSheet) {
                PlaybackSheet(
                    clip = streetClips().first(),
                    moments = moments,
                    onClose = { showSheet = false },
                    playerFactory = PlaybackPlayerFactory { player },
                )
            }
        }
        composeRule.onNodeWithTag("playback_moment_1").performClick()
        assertEquals(listOf(12_000L), player.seeks)
        composeRule.onNodeWithTag(PLAYBACK_CLOSE_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(true, player.released)
    }
}

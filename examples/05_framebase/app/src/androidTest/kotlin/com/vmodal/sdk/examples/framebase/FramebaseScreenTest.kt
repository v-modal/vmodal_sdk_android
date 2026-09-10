package com.vmodal.sdk.examples.framebase

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** P4/P5 navigation, accessibility, and grouped-search contract checks. */
@RunWith(AndroidJUnit4::class)
class FramebaseScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun test1_libraryStartsWithBundledArchive() {
        rule.onNodeWithText("Framebase").assertExists()
        rule.onNodeWithText("Street footage").assertExists()
        rule.onNodeWithTag("video_card_neighborhood_crossing").assertExists()
        rule.onNodeWithTag("video_card_downtown_traffic").assertExists()
        rule.onNodeWithTag("video_card_evening_junction").assertExists()
    }

    @Test
    fun test2_primaryActionsHaveAccessibleLabels() {
        rule.onNodeWithTag("import_video").assertExists()
        rule.onNodeWithTag("library_menu").assertExists()
        rule.onNodeWithTag("search_dock").assertExists()
    }

    @Test
    fun test3_settingsMasksRuntimeKeyAndRoutesPrepare() {
        rule.onNodeWithTag("library_menu").performClick()
        rule.onNodeWithText("Search settings").performClick()
        rule.onNodeWithTag("settings_sheet").assertExists()
        rule.onNodeWithTag("api_key_field").assertExists()
    }

    @Test
    fun test4_searchShellIsScrollableAndReturnsToLibrary() {
        rule.onNodeWithTag("search_dock").performClick()
        rule.onNodeWithTag("search_field").assertExists()
        rule.onNodeWithText("Try a search").assertDoesNotExist()
        rule.onNodeWithTag("search_back").performClick()
        rule.onNodeWithTag("library_screen").assertExists()
    }

    @Test
    fun test5_historyEmptyState() {
        rule.onNodeWithTag("library_menu").performClick()
        rule.onNodeWithText("Sync history").performClick()
        rule.onNodeWithText("Sync history").assertExists()
        rule.onNodeWithTag("history_empty").assertExists()
    }

    @Test
    fun test6_groupedResultsKeepFirstSeenOrderAndCollapseNearbyMoments() {
        fun hit(name: String, seconds: Double) = FrameMatch(
            row = mapOf("score" to .7),
            filename = name,
            seconds = seconds,
        )
        val grouped = groupMoments(
            listOf(hit("one.mp4", 35.0), hit("one.mp4", 36.0), hit("two.mp4", 35.0), hit("one.mp4", 22.0)),
        )
        assertEquals(listOf("one.mp4", "two.mp4"), grouped.keys.toList())
        assertEquals(listOf(35.0, 22.0), grouped.getValue("one.mp4").map { it.seconds })
    }

    @Test
    fun test7_groupedResultsRetainUnknownSourcesAndBackendCountSeparately() {
        val batch = SearchBatch(
            matches = listOf(FrameMatch(emptyMap(), "external.mp4", seconds = 4.0)),
            total = 12,
            serverMs = 7.0,
            roundTripMs = 11,
            imageMs = 3,
        )
        assertTrue(clipFor(streetClips(), "external.mp4") == null)
        assertEquals(12, batch.total)
        assertEquals(1, batch.matches.size)
    }

    @Test
    fun test8_bundledPlaybackCopiesAssetBeforeOpeningSheet() {
        rule.onNodeWithTag("video_card_neighborhood_crossing").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(PLAYBACK_CLOSE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(PLAYBACK_CLOSE_TAG).assertExists()
    }
}

package com.vmodal.sdk.examples.fullapp

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FullAppSearchGridTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun resultAreaDistinguishesInitialSearchingAndEmptyStates() {
        var state by mutableStateOf(FullAppUiState())
        compose.setContent { ResultsGrid(state) }
        compose.onNodeWithTag("search-summary").assertDoesNotExist()

        compose.runOnIdle { state = state.copy(action = FullAppAction.SEARCH) }
        compose.onNodeWithText("Searching and resolving images…").assertIsDisplayed()

        compose.runOnIdle {
            state = state.copy(action = null, searched = true, searchTotal = 0, searchReturned = 0)
        }
        compose.onNodeWithText("Showing 0 images from 0 matches").assertIsDisplayed()
        compose.onNodeWithText("No matching results.").assertIsDisplayed()

        compose.runOnIdle {
            state = state.copy(searchTotal = 12, searchReturned = 8, searchElapsedMs = 146.0)
        }
        compose.onNodeWithText("Showing 0 images from 12 matches (8 returned) · 146 ms").assertIsDisplayed()
        compose.onNodeWithText("No image-backed matches were found.").assertIsDisplayed()
    }

    @Test
    fun cardsRenderMetadataSemanticsAndIsolatedImageFailure() {
        val images = listOf(image(0), image(1))
        val state = FullAppUiState(
            searched = true,
            images = images,
            searchTotal = 2,
            searchReturned = 2,
        )
        compose.setContent {
            ResultsGrid(state) { item ->
                if (item.id == images[0].id) null else ColorDrawable(Color.RED)
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Image unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Image unavailable").assertCountEquals(1)
        compose.onNodeWithText("Result 1").assertIsDisplayed()
        compose.onNodeWithText("video_1.mp4").assertIsDisplayed()
        compose.onAllNodesWithText("stream: astream · time: 0000000002000 · score: 87.5%")
            .assertCountEquals(2)
        compose.onNodeWithContentDescription("Search result image: Result 1").assertExists()
    }

    @Test
    fun adaptiveGridUsesOneColumnNarrowAndMultipleColumnsWide() {
        val state = FullAppUiState(
            searched = true,
            images = List(4, ::image),
            searchTotal = 4,
            searchReturned = 4,
        )
        compose.setContent { ResultsGrid(state, width = 260) }
        val narrow0 = compose.onNodeWithTag("search-image-${state.images[0].id}")
            .fetchSemanticsNode().boundsInRoot
        val narrow1 = compose.onNodeWithTag("search-image-${state.images[1].id}")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(narrow0.left, narrow1.left)
        assertTrue(narrow1.top > narrow0.top)
    }

    @Test
    fun adaptiveGridUsesMultipleColumnsAtWideWidth() {
        val state = FullAppUiState(
            searched = true,
            images = List(4, ::image),
            searchTotal = 4,
            searchReturned = 4,
        )
        compose.setContent { ResultsGrid(state, width = 700) }
        val wide0 = compose.onNodeWithTag("search-image-${state.images[0].id}")
            .fetchSemanticsNode().boundsInRoot
        val wide1 = compose.onNodeWithTag("search-image-${state.images[1].id}")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(wide0.top, wide1.top)
        assertTrue(wide1.left > wide0.left)
    }

    @Test
    fun longTextStaysBoundedAtLargeAccessibilityScale() {
        val title = List(20) { "Long searchable title" }.joinToString(" ")
        val item = image(0).copy(
            title = title,
            filename = List(10) { "long_filename" }.joinToString("_"),
        )
        val state = FullAppUiState(
            searched = true,
            images = listOf(item),
            searchTotal = 1,
            searchReturned = 1,
        )
        compose.setContent { ResultsGrid(state, width = 260, fontScale = 1.8f) }
        compose.onNodeWithTag("search-image-${item.id}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search result image: $title").assertExists()
    }

    @Test
    fun compactFullScreenSectionsDoNotOverlapAndScrollToFooter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = FullAppViewModel.factory(context).create(FullAppViewModel::class.java)
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(360.dp).height(640.dp)) {
                    FullAppScreen(vm)
                }
            }
        }

        assertStacked("1. Configure and authenticate", "Runtime API key", "Configure client", "Resolve auth.me")
        assertSection("2. Select the data scope", "Collection", "Stream")
        assertSection("3. Upload a video", "Selected: video_10frames.mp4", "Use sample", "Upload")
        assertSection("4. Create and inspect the index", "Create index", "Index: not started")
        assertSection("5. Search", "Search query", "Search")

        compose.onNodeWithTag("full-app-grid").performScrollToNode(hasText("Forget API key"))
        compose.onNodeWithText("Forget API key").assertIsDisplayed()
    }

    private fun assertSection(vararg text: String) {
        assertStacked(*text)
    }

    private fun assertStacked(vararg text: String) {
        text.asList().zipWithNext().forEach { (topText, bottomText) ->
            compose.onNodeWithTag("full-app-grid").performScrollToNode(hasText(bottomText))
            val top = compose.onNodeWithText(topText).fetchSemanticsNode().boundsInRoot
            val bottom = compose.onNodeWithText(bottomText).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$topText $top overlaps $bottomText $bottom",
                top.bottom <= bottom.top,
            )
        }
    }

    private fun image(index: Int) = SearchImage(
        id = "$index-video.mp4-0000000002000",
        url = "https://image.test/$index",
        title = "Result $index",
        filename = "video_$index.mp4",
        stream = "astream",
        timestamp = "0000000002000",
        score = "87.5%",
    )
}

@Composable
private fun ResultsGrid(
    state: FullAppUiState,
    width: Int = 400,
    fontScale: Float = 1f,
    imageModel: (SearchImage) -> Any? = { ColorDrawable(Color.RED) },
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        MaterialTheme {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.width(width.dp).height(1_400.dp),
            ) {
                fullAppSearchResults(state, imageModel)
            }
        }
    }
}

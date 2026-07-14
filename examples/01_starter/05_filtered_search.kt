package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.SearchRequest
import com.vmodal.sdk.SearchResponse

fun searchWithFilters(sdk: Client): SearchResponse {
    val request = SearchRequest(
        queryText = "forklift",
        queryMetadata = mapOf("site" to "warehouse-a"),
        mode = "vid_file",
        groupName = "safety-review",
        searchSources = listOf("ocr", "asr", "image"),
        searchCombineMode = "union",
        startDate = "2026-07-01",
        endDate = "2026-07-14",
        textEmbScoreMin = 0.92,
        limit = 50,
    )
    return sdk.searches.searchVideo(request)
}

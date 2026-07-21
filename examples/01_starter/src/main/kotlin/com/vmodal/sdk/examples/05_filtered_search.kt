package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.SearchRequest
import com.vmodal.sdk.SearchResponse

fun searchWithFilters(sdk: Client): SearchResponse {
    val group = sdk.collections.listGroups("vid_file")
        .findGroup("safety-review", "vid_file")
        ?: error("safety-review is not available for this API key")
    val version = group.latestLancedbVersion
        ?: error("safety-review has no searchable LanceDB version")
    val request = SearchRequest(
        queryText = "forklift",
        queryMetadata = mapOf("site" to "warehouse-a"),
        mode = "vid_file",
        groupName = group.groupName,
        searchSources = listOf("ocr", "asr", "image"),
        searchCombineMode = "union",
        startDate = "2026-07-01",
        endDate = "2026-07-14",
        textEmbScoreMin = 0.92,
        limit = 50,
        versionLancedb = version,
    )
    return sdk.searches.searchVideo(request)
}

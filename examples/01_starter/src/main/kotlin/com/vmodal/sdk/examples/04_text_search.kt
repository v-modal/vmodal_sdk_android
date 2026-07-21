package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.SearchResponse

fun searchTrafficVideos(sdk: Client): SearchResponse {
    val group = sdk.collections.listGroups("vid_file")
        .findGroup("traffic-cameras", "vid_file")
        ?: error("traffic-cameras is not available for this API key")
    val version = group.latestLancedbVersion
        ?: error("traffic-cameras has no searchable LanceDB version")
    val result = sdk.searches.searchVideo(
        queryText = "red car entering the parking lot",
        groupName = group.groupName,
        streamName = "astream",
        limit = 20,
        versionLancedb = version,
    )
    println("returned=${result.cntActual} total=${result.cntTotal}")
    return result
}

package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.SearchResponse

fun searchTrafficVideos(sdk: Client): SearchResponse {
    val result = sdk.searches.searchVideo(
        queryText = "red car entering the parking lot",
        groupName = "traffic-cameras",
        streamName = "astream",
        limit = 20,
    )
    println("returned=${result.cntActual} total=${result.cntTotal}")
    return result
}

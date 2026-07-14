package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.IndexationStatusResponse

fun createAndInspectIndex(sdk: Client): IndexationStatusResponse {
    val created = sdk.indexes.createIndex(
        mode = "vid_file",
        groupName = "field-tests",
        modality = "vid_raw",
        streamName = "astream",
        version = "new_version",
    )
    val status = sdk.indexes.indexStatus(created.jobId)

    // Preview deletion before repeating with confirm=true.
    sdk.indexes.deleteIndex(
        mode = "vid_file",
        groupName = "field-tests",
        version = "old-version",
        dryRun = true,
        confirm = false,
    )
    return status
}

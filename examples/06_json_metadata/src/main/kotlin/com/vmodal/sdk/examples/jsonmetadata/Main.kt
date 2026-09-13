package com.vmodal.sdk.examples.jsonmetadata

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.vmodal.sdk.Client
import com.vmodal.sdk.IndexationStatusResponse
import com.vmodal.sdk.SearchRequest
import com.vmodal.sdk.SearchResponse
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.filePart
import com.vmodal.sdk.videoUpload
import java.io.File

private const val MODE = "vid_file"
private val okStatus = setOf("success", "succeeded", "done", "completed", "complete", "ok")
private val failStatus = setOf("failed", "failure", "error", "cancelled", "canceled", "dead_letter")
private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
private val jsonAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(mapType)

private data class Gps(val lat: String, val lon: String)

private data class MetadataRow(
    val video: String,
    val gps: Gps,
    val timestamp: String,
    val category: String,
    val tags: List<String>,
    val json: String,
) {
    val stream: String = File(video).nameWithoutExtension.take(8)
}

fun main(args: Array<String>) {
    require(args.size >= 2) {
        "Usage: <tokyo_walk.mp4> <tokyo_run.mp4> [group] [metadata.jsonl] [allowOverlap]"
    }
    val videos = listOf(File(args[0]).absoluteFile, File(args[1]).absoluteFile)
    val group = args.getOrNull(2)?.trim().orEmpty().ifBlank { "gps_metadata_demo" }
    val metadata = File(args.getOrNull(3) ?: "metadata.jsonl").absoluteFile
    val allowOverlap = args.getOrNull(4)?.toBooleanStrictOrNull() ?: false
    val rows = metadataLoad(metadata)
    metadataValidate(videos, rows)

    val sdk = Client.fromEnv()
    upload(sdk, videos, rows, group, allowOverlap)
    val job = sdk.indexes.createIndex(
        mode = MODE,
        groupName = group,
        streamName = null,
        version = "new_version",
        reProcess = true,
    )
    check(job.jobId.isNotBlank()) { "Index creation returned no job_id: ${job.raw}" }
    waitIndex(sdk, job.jobId)
    val version = waitVersion(sdk, group)

    val first = rows.first()
    search(
        sdk = sdk,
        rows = rows,
        row = first,
        group = group,
        version = version,
        query = "city street in Tokyo",
        tags = listOf(gpsTag(first.gps), timestampTag(first.timestamp)),
    )
    val category = rows.firstOrNull { it.category == "run" }
        ?: error("metadata does not contain category=run")
    search(
        sdk = sdk,
        rows = rows,
        row = category,
        group = group,
        version = version,
        query = "person running",
        tags = listOf(categoryTag(category.category)),
    )
}

private fun metadataLoad(file: File): List<MetadataRow> {
    require(file.isFile) { "Metadata file not found: $file" }
    val rows = file.readLines().mapIndexedNotNull { index, value ->
        val json = value.trim()
        if (json.isBlank()) return@mapIndexedNotNull null
        val raw = jsonAdapter.fromJson(json)
            ?: error("Metadata line ${index + 1} is not a JSON object")
        val gps = raw["gps"] as? Map<*, *>
            ?: error("Metadata line ${index + 1} must contain gps")
        MetadataRow(
            video = textRequired(raw["video"], "video", index),
            gps = Gps(
                textRequired(gps["lat"], "gps.lat", index),
                textRequired(gps["lon"], "gps.lon", index),
            ),
            timestamp = textRequired(raw["timestamp"], "timestamp", index),
            category = textRequired(raw["category"], "category", index),
            tags = (raw["tags"] as? List<*>)?.map(Any?::toString)
                ?: error("Metadata line ${index + 1} must contain tags"),
            json = json,
        )
    }
    require(rows.isNotEmpty()) { "Metadata file contains no JSON objects" }
    return rows
}

private fun textRequired(value: Any?, field: String, index: Int): String =
    value?.toString()?.trim()?.takeIf(String::isNotBlank)
        ?: error("Metadata line ${index + 1} must contain $field")

private fun metadataValidate(videos: List<File>, rows: List<MetadataRow>) {
    require(videos.size == rows.size) { "Provide one video path for every metadata row" }
    videos.forEach { require(it.isFile) { "Video file not found: $it" } }
    require(videos.map(File::getName).toSet() == rows.map(MetadataRow::video).toSet()) {
        "Metadata video values must match uploaded filenames: ${videos.map(File::getName)}"
    }
    require(rows.map(MetadataRow::stream).distinct().size == rows.size) {
        "The first eight characters of each video name must be unique"
    }
    rows.forEach { row ->
        val required = listOf(gpsTag(row.gps), timestampTag(row.timestamp), categoryTag(row.category))
        require(row.tags.containsAll(required)) { "${row.video} is missing derived tags: ${required - row.tags.toSet()}" }
    }
}

private fun upload(
    sdk: Client,
    videos: List<File>,
    rows: List<MetadataRow>,
    group: String,
    allowOverlap: Boolean,
) {
    val videosByName = videos.associateBy(File::getName)
    rows.forEach { row ->
        val video = requireNotNull(videosByName[row.video])
        val uploaded = sdk.collections.videoUpload(
            source = UploadSource.fromFile(video, "video/mp4"),
            collectionName = group,
            subCollectionName = row.stream,
            mode = MODE,
            modality = "vid_raw",
        )
        check(uploaded.uploaded) { "Video upload failed: ${uploaded.raw}" }

        val part = File.createTempFile("vmodal_metadata_", ".jsonl")
        try {
            part.writeText(row.json + "\n")
            val result = sdk.collections.uploadMetadataJsonl(
                part = filePart("file", part, "application/json"),
                mode = MODE,
                groupName = group,
                streamName = row.stream,
                writeMode = "overwrite",
                allowOverlap = allowOverlap,
            )
            println("uploaded video=${row.video} stream=${row.stream} metadata=${result.raw}")
        } finally {
            part.delete()
        }
    }
}

private fun waitIndex(sdk: Client, jobId: String): IndexationStatusResponse {
    val end = System.currentTimeMillis() + 30 * 60 * 1_000L
    while (System.currentTimeMillis() <= end) {
        val result = sdk.indexes.indexStatus(jobId)
        val status = result.status.trim().lowercase()
        println("indexation job_id=$jobId status=$status")
        if (status in okStatus) return result
        check(status !in failStatus) { "Indexation failed: ${result.raw}" }
        Thread.sleep(10_000)
    }
    error("Indexation timed out after 30 minutes")
}

private fun waitVersion(sdk: Client, group: String): Int {
    val end = System.currentTimeMillis() + 120_000L
    while (System.currentTimeMillis() <= end) {
        sdk.collections.listGroups(MODE).findGroup(group, MODE)?.latestLancedbVersion?.let { return it }
        Thread.sleep(3_000)
    }
    error("Collection $group has no searchable LanceDB version")
}

private fun search(
    sdk: Client,
    rows: List<MetadataRow>,
    row: MetadataRow,
    group: String,
    version: Int,
    query: String,
    tags: List<String>,
): SearchResponse {
    require(row.tags.containsAll(tags)) { "Search tags are absent from ${row.video}: $tags" }
    val result = sdk.searches.searchVideo(
        request = SearchRequest(
            queryText = query,
            mode = MODE,
            groupName = group,
            streamName = row.stream,
            limit = 10,
            versionLancedb = version,
        ),
        queryJsonField = mapOf("tags" to tags.joinToString(" AND ")),
    )
    check(result.cntActual > 0) { "Search returned no frames for tags=$tags" }

    val rowsByStream = rows.associateBy(MetadataRow::stream)
    result.data.mapNotNull(::stringMap).forEach { hit ->
        val stream = (hit["stream"] ?: hit["stream_name"])?.toString().orEmpty()
        val found = rowsByStream[stream]
            ?: error("Search hit returned an unknown metadata stream: $stream")
        println(
            "hit item_id=${hit["item_id"]} stream=$stream " +
                "gps=${found.gps.lat},${found.gps.lon} timestamp=${found.timestamp} " +
                "category=${found.category} tags=${found.tags} indexed_text=${hit["text_agg_tok"]}"
        )
    }
    return result
}

private fun stringMap(value: Any?): Map<String, Any?>? =
    (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

private fun tagValue(value: String): String = value.trim().replace("-", "m").replace(".", "_")
private fun gpsTag(gps: Gps): String = "gps_lat_${tagValue(gps.lat)}_lon_${tagValue(gps.lon)}"
private fun timestampTag(value: String): String = "timestamp_${tagValue(value)}"
private fun categoryTag(value: String): String = "category_${tagValue(value)}"

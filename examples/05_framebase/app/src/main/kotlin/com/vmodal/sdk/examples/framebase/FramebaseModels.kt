package com.vmodal.sdk.examples.framebase

import java.text.SimpleDateFormat
import java.math.BigDecimal
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.floor

const val ARCHIVE_COLLECTION = "framebase_streets"
const val ARCHIVE_STREAM = "street_study"
const val MAX_ARCHIVE_EVENTS = 40
const val MAX_IMPORT_BYTES = 100L * 1024L * 1024L

data class ArchiveClip(
    val id: String,
    val title: String,
    val location: String,
    val durationSec: Double,
    val assetPath: String? = null,
    val posterAssetPath: String? = null,
    val localPath: String? = null,
    val uploaded: Boolean = false,
    val bundled: Boolean = assetPath != null,
) {
    init {
        require(durationSec.isFinite() && durationSec >= 0.0) { "durationSec must be finite and non-negative" }
    }

    val remoteFilename: String get() = "$id.mp4"
    val city: String get() = location.substringBefore(" · ").trim()
}

data class ArchiveEvent(
    val title: String,
    val detail: String,
    val isError: Boolean = false,
    val time: String = isoNow(),
)

data class ArchiveState(
    val clips: List<ArchiveClip> = streetClips(),
    val pendingJobId: String = "",
    val accountId: String = "",
    val events: List<ArchiveEvent> = emptyList(),
)

data class FrameMatch(
    val row: Map<String, Any?>,
    val filename: String,
    val timestamp: String = "",
    val seconds: Double? = null,
    val imageBytes: ByteArray? = null,
    val imageUrl: String? = null,
) {
    val distance: Double? get() = number(row["score"])
}

data class SearchBatch(
    val matches: List<FrameMatch>,
    val total: Int,
    val serverMs: Double,
    val roundTripMs: Long,
    val imageMs: Long,
)

fun streetClips(): List<ArchiveClip> = listOf(
    ArchiveClip(
        id = "neighborhood_crossing",
        title = "Neighborhood crossing",
        location = "San Francisco · Daylight",
        durationSec = 55.2,
        assetPath = "videos/neighborhood_crossing.mp4",
        posterAssetPath = "stills/neighborhood_crossing.jpg",
    ),
    ArchiveClip(
        id = "downtown_traffic",
        title = "Downtown traffic",
        location = "Singapore · Afternoon",
        durationSec = 15.8,
        assetPath = "videos/downtown_traffic.mp4",
        posterAssetPath = "stills/downtown_traffic.jpg",
    ),
    ArchiveClip(
        id = "evening_junction",
        title = "Evening junction",
        location = "Mexico City · Dusk",
        durationSec = 75.0,
        assetPath = "videos/evening_junction.mp4",
        posterAssetPath = "stills/evening_junction.jpg",
    ),
)

fun firstText(row: Map<String, Any?>, fields: List<String>): String {
    for (field in fields) {
        val value = row[field]?.toString()?.trim().orEmpty()
        if (value.isNotEmpty() && value != "null") return value
    }
    return ""
}

fun basename(path: String): String = path.replace('\\', '/').substringAfterLast('/')

fun hitFilename(row: Map<String, Any?>): String {
    val direct = firstText(
        row,
        listOf("filename", "filename_sanitized", "video_filename", "video", "source_path", "path", "title"),
    )
    if (direct.isNotEmpty()) return basename(direct)

    var itemId = firstText(row, listOf("item_id"))
    val stream = firstText(row, listOf("stream", "stream_name"))
    val timestamp = firstText(row, listOf("ts_unix", "ts_unix_13digits", "timestamp_ms"))
    if (stream.isNotEmpty() && itemId.startsWith("$stream-")) {
        itemId = itemId.removePrefix("$stream-")
    }
    if (timestamp.isNotEmpty() && itemId.endsWith("-$timestamp")) {
        itemId = itemId.removeSuffix("-$timestamp")
    }
    return basename(itemId)
}

fun timestamp13(row: Map<String, Any?>): String {
    val raw = listOf("ts_unix_13digits", "ts_unix", "timestamp_ms")
        .asSequence()
        .mapNotNull { row[it] }
        .mapNotNull { integerDigits(it) }
        .firstOrNull()
        ?: return ""
    return when {
        raw.length >= 13 -> raw.substring(0, 13)
        raw.length == 10 -> raw + "000"
        else -> raw.padStart(13, '0')
    }
}

fun hitSeconds(row: Map<String, Any?>): Double? {
    val fields = listOf(
        "video_time_seconds",
        "timestamp_seconds",
        "time_seconds",
        "start_seconds",
        "offset_seconds",
        "seconds",
        "time_sec",
    )
    for (field in fields) {
        val value = number(row[field])
        if (value != null && value.isFinite() && value >= 0.0) return value
    }
    val relative = listOf("ts_unix_13digits", "ts_unix", "timestamp_ms")
        .asSequence()
        .mapNotNull { number(row[it]) }
        .firstOrNull { it.isFinite() && it >= 0.0 }
    return if (relative != null && relative.isFinite() && relative >= 0.0 && relative < 86_400_000.0) {
        relative / 1000.0
    } else {
        null
    }
}

fun clipFor(clips: List<ArchiveClip>, filename: String): ArchiveClip? {
    val clean = basename(filename).lowercase(Locale.US)
    return clips.firstOrNull { clip ->
        val id = clip.id.lowercase(Locale.US)
        clean == id || clean == clip.remoteFilename.lowercase(Locale.US) || clean.startsWith("$id.")
    }
}

fun groupMoments(matches: List<FrameMatch>, gapSeconds: Double = 6.0): LinkedHashMap<String, List<FrameMatch>> {
    val grouped = LinkedHashMap<String, MutableList<FrameMatch>>()
    for (match in matches) {
        val retained = grouped.getOrPut(match.filename) { mutableListOf() }
        val seconds = match.seconds
        val nearby = seconds != null && retained.any { old ->
            old.seconds != null && kotlin.math.abs(old.seconds - seconds) < gapSeconds
        }
        if (!nearby) retained += match
    }
    return LinkedHashMap(grouped.mapValues { it.value.toList() })
}

fun boundedEvents(events: List<ArchiveEvent>): List<ArchiveEvent> = events.take(MAX_ARCHIVE_EVENTS)

fun prependEvent(events: List<ArchiveEvent>, event: ArchiveEvent): List<ArchiveEvent> =
    boundedEvents(listOf(event) + events)

fun indexDone(state: String): Boolean = state.trim().lowercase(Locale.US) in
    setOf("success", "succeeded", "done", "completed", "ok")

fun indexFailed(state: String): Boolean = state.trim().lowercase(Locale.US) in
    setOf("failed", "failure", "error", "cancelled", "canceled")

fun timeLabel(seconds: Double): String {
    val value = if (seconds.isFinite()) floor(seconds).toInt().coerceIn(0, 86_400) else 0
    return "%02d:%02d".format(Locale.US, value / 60, value % 60)
}

fun durationLabel(seconds: Double): String = timeLabel(seconds)

fun playbackSeconds(row: Map<String, Any?>): Double? = hitSeconds(row)

fun filenameMatches(clip: ArchiveClip, filename: String): Boolean = clipFor(listOf(clip), filename) != null

fun newImportedClip(id: String, title: String, durationSec: Double, localPath: String): ArchiveClip =
    ArchiveClip(
        id = id,
        title = title,
        location = "Imported recording",
        durationSec = durationSec,
        localPath = localPath,
        bundled = false,
    )

fun resetForAccount(state: ArchiveState, userId: String): ArchiveState {
    if (userId.isBlank() || state.accountId == userId) {
        return state.copy(accountId = userId.ifBlank { state.accountId })
    }
    return state.copy(
        accountId = userId,
        clips = state.clips.map { it.copy(uploaded = false) },
        pendingJobId = "",
        events = emptyList(),
    )
}

private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}.format(Date())

private fun number(value: Any?): Double? = when (value) {
    null -> null
    is Number -> value.toDouble()
    else -> value.toString().trim().toDoubleOrNull()
}

private fun integerDigits(value: Any?): String? {
    val text = value?.toString()?.trim() ?: return null
    if (text.isEmpty() || text.equals("nan", true) || text.equals("infinity", true) || text.equals("-infinity", true)) return null
    return try {
        val decimal = BigDecimal(text)
        if (decimal.signum() < 0) null else decimal.toBigInteger().toString()
    } catch (_: NumberFormatException) {
        null
    }
}

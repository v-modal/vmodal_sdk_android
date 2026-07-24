package com.vmodal.sdk.examples.fullapp

import com.vmodal.sdk.Client
import com.vmodal.sdk.UploadSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullAppLiveRetrievalTest {
    @Test
    fun liveIndexationAndBlueRetrieval() = runBlocking {
        if (System.getProperty("vmodalLive03Fullapp") != "true") return@runBlocking

        val key = System.getenv("VMODAL_API_KEY").orEmpty().trim()
        require(key.isNotBlank()) { "VMODAL_API_KEY is required for the full-app live test" }
        val source = File(System.getProperty("vmodalFullappFixture").orEmpty()).absoluteFile
        check(source.isFile && source.length() > 0) { "missing full-app fixture: $source" }

        val id = UUID.randomUUID().toString().replace("-", "").take(10)
        val group = "sdk_android_fullapp_$id".take(32)
        val stream = "astream_$id"
        val repo = FullAppRepository()
        val sdk = Client.fromEnv()
        var uploaded = false
        var indexed = false
        var version = ""
        var primary: Throwable? = null

        try {
            repo.configure(key)
            repo.resolveIdentity()
            val filename = repo.upload(UploadSource.fromFile(source, "video/mp4"), group, stream) {}
            uploaded = true
            assertEquals(source.name, filename)

            val job = repo.createIndex(group, stream)
            indexed = true
            assertTrue(job.jobId.isNotBlank(), "index creation returned no job ID")
            liveWaitIndex(repo, job.jobId)
            val indexVersion = liveWaitVersion(sdk, group)
            version = "v$indexVersion"

            val output = liveWaitSearch(repo, group, stream)
            val stem = source.nameWithoutExtension.lowercase()
            val image = requireNotNull(output.images.firstOrNull { stem in it.filename.lowercase() }) {
                "blue search did not resolve an image for ${source.name}"
            }
            check(image.bytes.isNotEmpty()) { "signed image content retrieval returned an empty body" }
            check(strEncodedImageKind(image.bytes) != null) {
                "signed image content is not a supported encoded image"
            }
            println("full-app live upload/index/blue retrieval passed for $group")
        } catch (exc: Throwable) {
            primary = exc
            throw exc
        } finally {
            val cleanup = liveCleanup(sdk, group, uploaded, indexed, version)
            if (cleanup != null) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun liveWaitIndex(repo: FullAppRepository, jobId: String) {
        val end = System.currentTimeMillis() + 1_800_000
        var last = ""
        while (System.currentTimeMillis() <= end) {
            last = repo.indexStatus(jobId).status.trim().lowercase()
            println("full-app indexation status job_id=$jobId status=$last")
            if (last in okStatus) return
            check(last !in failStatus) { "full-app indexation failed with status $last" }
            delay(10_000)
        }
        error("full-app indexation timed out with status $last")
    }

    private suspend fun liveWaitVersion(sdk: Client, group: String): Int {
        val end = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() <= end) {
            sdk.collections.listGroups("vid_file").findGroup(group, "vid_file")
                ?.latestLancedbVersion
                ?.let { return it }
            delay(3_000)
        }
        error("full-app collection $group has no advertised LanceDB version")
    }

    private suspend fun liveWaitSearch(repo: FullAppRepository, group: String, stream: String): SearchOutput {
        val end = System.currentTimeMillis() + 180_000
        var last: SearchOutput? = null
        while (System.currentTimeMillis() <= end) {
            last = repo.search("blue", group, stream)
            if (last.images.isNotEmpty()) return last
            delay(5_000)
        }
        error("full-app blue search returned no images: total=${last?.total}, returned=${last?.returned}")
    }

    private companion object {
        val okStatus = setOf("success", "succeeded", "done", "completed", "ok")
        val failStatus = setOf("failed", "failure", "error", "cancelled", "canceled", "dead_letter")
    }
}

private fun liveCleanup(
    sdk: Client,
    group: String,
    uploaded: Boolean,
    indexed: Boolean,
    version: String,
): Throwable? {
    var first: Throwable? = null
    if (indexed) {
        try {
            sdk.indexes.deleteIndex(
                mode = "vid_file",
                groupName = group,
                version = version.ifBlank { "all" },
                modality = "vid_img_emb",
                confirm = true,
            )
        } catch (exc: Throwable) {
            if (exc !is com.vmodal.sdk.ApiError || exc.statusCode != 404) first = exc
        }
    }
    if (uploaded) {
        try {
            sdk.collections.delete(group, "vid_file", scope = "all", confirm = true)
        } catch (exc: Throwable) {
            if (exc !is com.vmodal.sdk.ApiError || exc.statusCode != 404) {
                if (first == null) first = exc else first.addSuppressed(exc)
            }
        }
    }
    return first
}

private fun strEncodedImageKind(value: ByteArray): String? = when {
    intPngSize(value) -> "png"
    intJpegSize(value) -> "jpeg"
    intGifSize(value) -> "gif"
    intWebpSize(value) -> "webp"
    else -> null
}

private fun intPngSize(value: ByteArray): Boolean =
    value.size >= 24 &&
        value.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) &&
        intBigEndian(value, 16, 4) > 0 && intBigEndian(value, 20, 4) > 0

private fun intGifSize(value: ByteArray): Boolean =
    value.size >= 10 &&
        String(value, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") &&
        intLittleEndian(value, 6, 2) > 0 && intLittleEndian(value, 8, 2) > 0

private fun intWebpSize(value: ByteArray): Boolean =
    value.size >= 30 &&
        String(value, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(value, 8, 4, Charsets.US_ASCII) == "WEBP"

private fun intJpegSize(value: ByteArray): Boolean {
    if (value.size < 12 || value[0].toInt().and(255) != 255 || value[1].toInt().and(255) != 216) return false
    var offset = 2
    val frames = setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207)
    while (offset + 8 < value.size) {
        if (value[offset].toInt().and(255) != 255) {
            offset++
            continue
        }
        val marker = value[offset + 1].toInt().and(255)
        if (marker == 217 || marker == 218) return false
        if (marker in frames) {
            return intBigEndian(value, offset + 5, 2) > 0 && intBigEndian(value, offset + 7, 2) > 0
        }
        val size = intBigEndian(value, offset + 2, 2)
        if (size < 2) return false
        offset += size + 2
    }
    return false
}

private fun intBigEndian(value: ByteArray, offset: Int, count: Int): Int {
    if (offset < 0 || count <= 0 || offset + count > value.size) return 0
    var result = 0
    repeat(count) { index -> result = result.shl(8) or value[offset + index].toInt().and(255) }
    return result
}

private fun intLittleEndian(value: ByteArray, offset: Int, count: Int): Int {
    if (offset < 0 || count <= 0 || offset + count > value.size) return 0
    var result = 0
    repeat(count) { index -> result = result or (value[offset + index].toInt().and(255) shl (8 * index)) }
    return result
}

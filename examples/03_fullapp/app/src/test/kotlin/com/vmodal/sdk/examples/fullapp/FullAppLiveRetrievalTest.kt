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
            assertTrue(sdk.images.getImageFromUrl(image.url).isNotEmpty(), "blue result image retrieval was empty")
            println("full-app live upload/index/blue retrieval passed for $group")
        } finally {
            if (indexed) {
                sdk.indexes.deleteIndex(
                    mode = "vid_file",
                    groupName = group,
                    version = version.ifBlank { "all" },
                    modality = "vid_img_emb",
                    confirm = true,
                )
            }
            if (uploaded) {
                sdk.collections.delete(group, "vid_file", scope = "all", confirm = true)
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

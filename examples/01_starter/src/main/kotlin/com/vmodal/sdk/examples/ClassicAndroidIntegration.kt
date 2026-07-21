package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.GroupsResponse
import com.vmodal.sdk.UploadHandle
import com.vmodal.sdk.UploadProgress
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadResponse
import com.vmodal.sdk.videoUploadAsync
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Activity/Fragment-independent owner for classic Views.
 *
 * Supply a lifecycle-owned scope. SDK suspension keeps network work off the main thread, while
 * callbacks are marshalled back through that scope. [close] rejects stale callbacks and cancels
 * the active upload without retaining an Activity, Fragment, or View.
 */
class ClassicAndroidIntegration(
    private val client: Client,
    ownerScope: CoroutineScope,
) : Closeable {
    private val generation = AtomicLong()
    private val ownerJob = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + ownerJob)
    private var upload: UploadHandle? = null

    fun loadCollections(
        onSuccess: (GroupsResponse) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val current = generation.get()
        scope.launch {
            try {
                val groups = client.coroutines().collections.listGroups("vid_file")
                if (generation.get() == current) onSuccess(groups)
            } catch (error: Exception) {
                if (generation.get() == current) onFailure(error)
            }
        }
    }

    fun startUpload(
        source: UploadSource,
        scopeValue: CookbookScope,
        onProgress: (UploadProgress) -> Unit,
        onSuccess: (VideoUploadResponse) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        cancelUpload()
        val current = generation.get()
        upload = client.collections.videoUploadAsync(
            source = source,
            collectionName = scopeValue.collection,
            subCollectionName = scopeValue.stream,
            mode = scopeValue.mode,
            onProgress = { value -> scope.launch { if (generation.get() == current) onProgress(value) } },
            onSuccess = { value -> scope.launch { if (generation.get() == current) onSuccess(value) } },
            onFailure = { error -> scope.launch { if (generation.get() == current) onFailure(error) } },
        )
    }

    fun cancelUpload() {
        generation.incrementAndGet()
        upload?.cancel()
        upload = null
    }

    override fun close() {
        cancelUpload()
        ownerJob.cancel()
    }
}

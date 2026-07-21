package com.vmodal.sdk

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** Events emitted while one signed video upload is collected. */
sealed class VideoUploadEvent {
    /**
     * Latest bounded, monotonic byte progress reported by the upload transport.
     *
     * @property progress immutable uploaded and total byte counts
     */
    data class Progress(val progress: UploadProgress) : VideoUploadEvent()

    /**
     * The one final typed upload response. The flow closes after this event.
     *
     * @property response completed signed-upload result
     */
    data class Completed(val response: VideoUploadResponse) : VideoUploadEvent()
}

/**
 * Returns a cold upload flow. Each collection starts one independent upload.
 *
 * Progress uses a two-item, drop-oldest buffer so a slow collector cannot create an unbounded
 * queue. The latest progress and following completion fit together, so completion is never lost.
 * Cancelling collection cancels the upload handle and every signed call attached to it. Typed SDK
 * failures and cancellation retain their original types.
 */
internal fun CollectionsResource.videoUploadEvents(
    source: UploadSource,
    collectionName: String,
    subCollectionName: String,
    mode: String = "vid_file",
    modality: String = "vid_raw",
    ttl: Int = 12600,
    options: VideoUploadOptions = VideoUploadOptions(),
): Flow<VideoUploadEvent> = videoUploadEvents(source.contentLength) { onProgress, onSuccess, onFailure ->
    videoUploadAsync(
        source,
        collectionName,
        subCollectionName,
        mode,
        modality,
        ttl,
        options,
        onProgress,
        onSuccess,
        onFailure,
    )
}

internal fun videoUploadEvents(
    totalBytes: Long? = null,
    startUpload: (
        onProgress: (UploadProgress) -> Unit,
        onSuccess: (VideoUploadResponse) -> Unit,
        onFailure: (Exception) -> Unit,
    ) -> UploadHandle,
): Flow<VideoUploadEvent> = callbackFlow {
    val terminal = AtomicBoolean(false)
    val lastBytes = AtomicLong(0)
    var handle: UploadHandle? = null
    try {
        handle = startUpload(
            { progress ->
                val total = (totalBytes ?: progress.totalBytes).coerceAtLeast(0)
                val reported = progress.uploadedBytes.coerceIn(0, total)
                val uploaded = lastBytes.updateAndGet { previous -> maxOf(previous, reported).coerceAtMost(total) }
                if (!terminal.get()) trySend(VideoUploadEvent.Progress(UploadProgress(uploaded, total)))
            },
            { response ->
                if (terminal.compareAndSet(false, true)) {
                    trySend(VideoUploadEvent.Completed(response))
                    close()
                }
            },
            { error ->
                if (terminal.compareAndSet(false, true)) close(error)
            },
        )
    } catch (error: Throwable) {
        if (terminal.compareAndSet(false, true)) close(error)
    }
    awaitClose {
        if (terminal.compareAndSet(false, true)) handle?.cancel()
    }
}.buffer(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)

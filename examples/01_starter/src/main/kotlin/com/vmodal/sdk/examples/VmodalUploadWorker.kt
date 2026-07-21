package com.vmodal.sdk.examples

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vmodal.sdk.ApiError
import com.vmodal.sdk.Client
import com.vmodal.sdk.TransportError
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** The application supplies a session-scoped client without persisting credentials in WorkManager data. */
interface VmodalClientOwner {
    val vmodalClient: Client
    val vmodalAccountId: String

    /** Return true only after the app reconciles an ambiguous upload outcome. */
    suspend fun shouldRetryUpload(error: Exception, uri: Uri, collection: String, stream: String): Boolean = false
}

/**
 * Compile-checked WorkManager pattern for a long-running signed upload.
 *
 * The scheduling code must retain read permission for [KEY_URI]. WorkManager cancellation reaches
 * Flow collection and the SDK upload handle. Cancellation is rethrown and is never converted to a
 * retry. Only transient failures retry, and [runAttemptCount] bounds those retries.
 */
class VmodalUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val owner = applicationContext as? VmodalClientOwner ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val accountId = inputData.getString(KEY_ACCOUNT_ID)?.trim().orEmpty()
        val collection = inputData.getString(KEY_COLLECTION)?.trim().orEmpty()
        val stream = inputData.getString(KEY_STREAM)?.trim().orEmpty()
        if (accountId.isBlank() || owner.vmodalAccountId != accountId) return Result.failure()
        if (collection.isBlank() || stream.isBlank()) return Result.failure()

        return try {
            var fileName = ""
            owner.vmodalClient.coroutines().collections.videoUploadEvents(
                source = uploadSource(uri),
                collectionName = collection,
                subCollectionName = stream,
            ).collect { event ->
                when (event) {
                    is VideoUploadEvent.Progress -> setProgress(
                        workDataOf(KEY_PROGRESS to event.progress.percent),
                    )
                    is VideoUploadEvent.Completed -> {
                        check(event.response.uploaded) { "Upload did not complete" }
                        fileName = event.response.fileName
                    }
                }
            }
            Result.success(workDataOf(KEY_FILE_NAME to fileName))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val retry = runAttemptCount + 1 < MAX_ATTEMPTS &&
                error.isTransient() &&
                owner.shouldRetryUpload(error, uri, collection, stream)
            if (retry) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun uploadSource(uri: Uri): UploadSource {
        val resolver = applicationContext.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(size > 0) { "The selected video must report a stable non-empty size" }
        return UploadSource(
            fileName = inputData.getString(KEY_FILE_NAME)?.trim().orEmpty().ifBlank { "video.mp4" },
            contentLength = size,
            contentType = resolver.getType(uri) ?: "video/mp4",
            sourceId = uri.toString(),
        ) {
            resolver.openInputStream(uri) ?: error("Unable to open selected video")
        }
    }

    private fun Exception.isTransient(): Boolean =
        this is TransportError || this is ApiError && statusCode in TRANSIENT_STATUS

    companion object {
        const val KEY_URI = "uri"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_COLLECTION = "collection"
        const val KEY_STREAM = "stream"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_PROGRESS = "progress"
        private const val MAX_ATTEMPTS = 3
        private val TRANSIENT_STATUS = setOf(408, 429, 500, 502, 503, 504)
    }
}

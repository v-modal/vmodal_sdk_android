package com.vmodal.sdk

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Signed-upload policy including retry, resume, cancellation, and explicit
 * multipart behavior. Multipart is never selected implicitly by file size.
 *
 * @property multipart whether to use multipart upload
 * @property partSizeBytes target bytes per multipart part
 * @property maxConcurrency maximum parallel parts
 * @property maxPartAttempts maximum attempts per part
 * @property partTimeoutSeconds timeout for each part
 * @property resume whether to resume a compatible checkpoint
 * @property sessionStore multipart checkpoint store
 * @property adaptiveConditions optional platform conditions for adaptive tuning
 * @property videoFilename canonical CCTV filename sent when finalizing the upload
 * @property metadataText optional metadata text sent when finalizing the upload
 * @property metadataTags optional metadata tags sent when finalizing the upload
 * @property startDatetimeUser offset-aware CCTV footage start time
 * @property reProcess whether the uploaded video should be processed again
 */
data class VideoUploadOptions(
    val multipart: Boolean = false,
    /** Retained for source compatibility. Upload strategy is never selected by file size. */
    val multipartThresholdBytes: Long = 100L * 1024 * 1024,
    val partSizeBytes: Long = 64L * 1024 * 1024,
    val maxConcurrency: Int = 4,
    val maxPartAttempts: Int = 5,
    val partTimeoutSeconds: Long = 300,
    val resume: Boolean = true,
    val sessionStore: UploadSessionStore = UploadSessionStores.memory,
    val adaptiveConditions: UploadConditions? = null,
    val videoFilename: String? = null,
    val metadataText: String? = null,
    val metadataTags: List<String>? = null,
    val startDatetimeUser: String? = null,
    val reProcess: Boolean = false,
) {
    /**
     * Pre-upload transcoder. The default performs no transcoding. An Android app injects its own
     * device transcoder here, for example `options.transcoder = Media3TransformerTranscoder(ctx)`.
     * Kept outside the primary constructor to preserve binary compatibility of this data class.
     */
    var transcoder: VideoTranscoder = PassthroughTranscoder

    /** Applies the optional adaptive policy without mutating this value. Preserves [transcoder]. */
    fun resolvedFor(size: Long): VideoUploadOptions {
        if (!multipart) return this
        val conditions = adaptiveConditions ?: return this
        val preset = AdaptiveUploadPolicy.select(size, conditions)
        return copy(
            partSizeBytes = preset.partSizeBytes,
            maxConcurrency = preset.maxConcurrency,
            maxPartAttempts = preset.maxPartAttempts,
            partTimeoutSeconds = preset.partTimeoutSeconds,
        ).also { it.transcoder = transcoder }
    }

    /** Validates the source size and multipart settings. */
    fun validate(size: Long) {
        if (size > MAX_VIDEO_UPLOAD_BYTES) {
            throw ValidationFailed("video file too large: $size bytes exceeds the $MAX_VIDEO_UPLOAD_BYTES bytes (100 MB) limit")
        }
        if (!multipart) return
        if (partSizeBytes < 5L * 1024 * 1024) throw ValidationFailed("part_size_bytes must be at least 5 MiB")
        if (maxConcurrency !in 1..16) throw ValidationFailed("max_concurrency must be in 1..16")
        if (maxPartAttempts !in 1..10) throw ValidationFailed("max_part_attempts must be in 1..10")
        if (partTimeoutSeconds <= 0) throw ValidationFailed("part_timeout_seconds must be positive")
        if (size <= 0) throw ValidationFailed("multipart size must be positive")
        if (partCount(size, partSizeBytes) > 10_000) throw ValidationFailed("part_size_bytes would create more than 10,000 parts")
    }

    /** Shared upload limits. */
    companion object {
        /** Max size for a single video upload (100 MB). Larger files are rejected. */
        const val MAX_VIDEO_UPLOAD_BYTES: Long = 100L * 1024 * 1024
    }
}

/**
 * Starts a non-blocking, cancelable upload and returns its [UploadHandle]. A saturated high-level
 * queue invokes [onFailure] with [ValidationFailed] before this method returns.
 */
fun CollectionsResource.videoUploadAsync(
    source: UploadSource,
    collectionName: String,
    subCollectionName: String,
    mode: String = "vid_file",
    modality: String = "vid_raw",
    ttl: Int = 12600,
    options: VideoUploadOptions = VideoUploadOptions(),
    onProgress: (UploadProgress) -> Unit = {},
    onSuccess: (VideoUploadResponse) -> Unit,
    onFailure: (Exception) -> Unit,
): UploadHandle {
    val resolved = options.resolvedFor(source.contentLength)
    resolved.validate(source.contentLength)
    val handle = UploadHandle()
    // The orchestration runs off the UI thread and links every cancellable gateway and signed
    // storage request to the same operation handle.
    val admitted = uploadScheduler.execute(handle) {
        try {
            val result = videoUploadRun(source, collectionName, subCollectionName, mode, modality, ttl, resolved, handle, onProgress)
            handle.complete()
            if (!handle.isCanceled) onSuccess(result)
        } catch (exc: Exception) {
            handle.stop()
            handle.awaitQuiescence()
            if (!handle.isCanceled) onFailure(exc)
        }
    }
    if (!admitted) {
        handle.stop()
        onFailure(ValidationFailed("high-level upload queue is full"))
    }
    return handle
}

/** Blocking counterpart for workers and tests. Never call this method on Android's main thread. */
fun CollectionsResource.videoUpload(
    source: UploadSource,
    collectionName: String,
    subCollectionName: String,
    mode: String = "vid_file",
    modality: String = "vid_raw",
    ttl: Int = 12600,
    options: VideoUploadOptions = VideoUploadOptions(),
    onProgress: (UploadProgress) -> Unit = {},
): VideoUploadResponse {
    val resolved = options.resolvedFor(source.contentLength)
    resolved.validate(source.contentLength)
    val handle = UploadHandle()
    return uploadScheduler.runBlocking(handle) {
        try {
            videoUploadRun(source, collectionName, subCollectionName, mode, modality, ttl, resolved, handle, onProgress)
                .also { handle.complete() }
        } catch (exc: Exception) {
            handle.stop()
            handle.awaitQuiescence()
            throw exc
        }
    }
}

/** Uploads sources sequentially on the calling worker thread. */
fun CollectionsResource.videoUploadBulk(
    sources: List<UploadSource>,
    collectionName: String,
    subCollectionName: String,
    mode: String = "vid_file",
    modality: String = "vid_raw",
    ttl: Int = 12600,
    options: VideoUploadOptions = VideoUploadOptions(),
): VideoUploadBulkResponse {
    val data = sources.map { videoUpload(it, collectionName, subCollectionName, mode, modality, ttl, options) }
    return VideoUploadBulkResponse(mapOf("data" to data.map { it.raw }, "total" to data.size))
}

/**
 * Starts a cancelable bulk upload and reports aggregate byte progress. A saturated high-level
 * queue invokes [onFailure] with [ValidationFailed] before this method returns.
 */
fun CollectionsResource.videoUploadBulkAsync(
    sources: List<UploadSource>,
    collectionName: String,
    subCollectionName: String,
    mode: String = "vid_file",
    modality: String = "vid_raw",
    ttl: Int = 12600,
    options: VideoUploadOptions = VideoUploadOptions(),
    onProgress: (UploadProgress) -> Unit = {},
    onSuccess: (VideoUploadBulkResponse) -> Unit,
    onFailure: (Exception) -> Unit,
): UploadHandle {
    val resolved = sources.map { source -> options.resolvedFor(source.contentLength).also { it.validate(source.contentLength) } }
    val handle = UploadHandle()
    val admitted = uploadScheduler.execute(handle) {
        try {
            val total = sources.sumOf { it.contentLength }
            var completed = 0L
            val data = sources.zip(resolved).map { (source, itemOptions) ->
                val item = videoUploadRun(source, collectionName, subCollectionName, mode, modality, ttl, itemOptions, handle) { progress ->
                    onProgress(UploadProgress(completed + progress.uploadedBytes, total))
                }
                completed += source.contentLength
                item
            }
            handle.complete()
            if (!handle.isCanceled) onSuccess(VideoUploadBulkResponse(mapOf("data" to data.map { it.raw }, "total" to data.size)))
        } catch (exc: Exception) {
            handle.stop()
            handle.awaitQuiescence()
            if (!handle.isCanceled) onFailure(exc)
        }
    }
    if (!admitted) {
        handle.stop()
        onFailure(ValidationFailed("high-level upload queue is full"))
    }
    return handle
}

private fun CollectionsResource.videoUploadRun(
    source: UploadSource,
    collectionName: String,
    subCollectionName: String,
    mode: String,
    modality: String,
    ttl: Int,
    options: VideoUploadOptions,
    handle: UploadHandle,
    onProgress: (UploadProgress) -> Unit,
): VideoUploadResponse {
    if (!options.transcoder.isPassthrough) {
        return transcodeThenUpload(source, collectionName, subCollectionName, mode, modality, ttl, options, handle, onProgress)
    }
    return if (options.multipart) {
        try {
            multipartUpload(source, collectionName, subCollectionName, mode, modality, ttl, options, handle, onProgress)
        } catch (exc: ApiError) {
            if (exc.statusCode != 404) throw exc
            throw FeatureDisabled(
                "experimental multipart upload is unavailable on this gateway; retry with VideoUploadOptions(multipart = false)"
            ).also { it.addSuppressed(exc) }
        }
    } else {
        singleUpload(source, collectionName, subCollectionName, mode, modality, ttl, options, handle, onProgress)
    }
}

/**
 * Reduces the source through [VideoUploadOptions.transcoder], uploads the result, then removes any
 * temporary file the transcoder produced. The original file is canonical and is never deleted.
 * Parity with the reference Python SDK `video_upload(reduce_size=...)`.
 */
private fun CollectionsResource.transcodeThenUpload(
    source: UploadSource,
    collectionName: String,
    subCollectionName: String,
    mode: String,
    modality: String,
    ttl: Int,
    options: VideoUploadOptions,
    handle: UploadHandle,
    onProgress: (UploadProgress) -> Unit,
): VideoUploadResponse {
    val input = source.localFile
        ?: throw ValidationFailed("transcoding requires a file-backed UploadSource (use UploadSource.fromFile)")
    val sourceSize = input.length()
    val result = options.transcoder.reduce(input)
    // A transcoder that returns the original file (identity) is treated as a passthrough: no temp,
    // no cleanup. Only a produced file at a different path is uploaded and then deleted.
    val produced = result.output.canonicalFile != input.canonicalFile
    if (produced && (!result.output.isFile || result.output.length() <= 0)) {
        throw ValidationFailed("transcoder produced an empty video", details = result.output.path)
    }
    val uploadSource = if (produced) UploadSource.fromFile(result.output, source.contentType) else source
    // A bare copy() resets the (non-constructor) transcoder to the passthrough default, so the
    // recursive run uploads the reduced file directly instead of transcoding again.
    val inner = options.copy(
        videoFilename = options.videoFilename
            ?: if (options.startDatetimeUser != null) source.fileName else null,
    ).resolvedFor(uploadSource.contentLength)
    inner.validate(uploadSource.contentLength)
    val uploaded = videoUploadRun(uploadSource, collectionName, subCollectionName, mode, modality, ttl, inner, handle, onProgress)
    var deleted = false
    if (produced) {
        deleted = result.output.delete() || !result.output.exists()
        if (!deleted) {
            throw ApiError("upload completed but reduced temporary video still exists", body = mapOf("uploaded" to true))
        }
    }
    return VideoUploadResponse(uploaded.raw + mapOf(
        "reduce_size" to produced,
        "source_filepath_local" to input.absolutePath,
        "source_size_bytes" to sourceSize,
        "temporary_file_deleted" to (produced && deleted),
        "temporary_file_reused" to result.reused,
        "filepath_local" to input.absolutePath,
    ))
}

private fun CollectionsResource.singleUpload(
    source: UploadSource,
    collectionName: String,
    subCollectionName: String,
    mode: String,
    modality: String,
    ttl: Int,
    options: VideoUploadOptions,
    handle: UploadHandle,
    onProgress: (UploadProgress) -> Unit,
): VideoUploadResponse {
    val signed = http.requestUpload(
        "POST",
        Routes.full(Routes.externalUploadGetSignedUrl),
        handle,
        params = uploadParams(source, collectionName, subCollectionName, mode, modality, ttl),
    )
    val result = uploadAwait(source, signed["url"]?.toString().orEmpty(), signed["method"]?.toString() ?: "PUT", handle = handle, onProgress = onProgress)
    val done = uploadDone(
        signed["key"]?.toString().orEmpty(), source, collectionName,
        subCollectionName, mode, modality, options, handle,
    )
    val raw = linkedMapOf<String, Any?>().apply {
        putAll(signed)
        put("filename", source.fileName)
        put("size_bytes", source.contentLength)
        put("status_code", result.statusCode)
        put("uploaded", true)
        put("upload_strategy", "single")
        put("etag", result.etag)
        put("part_count", 1)
        put("parts_uploaded", 1)
        put("attempt_count", 1)
        put("upload_done", done)
        put("dest_path", done["dest_path"]?.toString().orEmpty())
        put("video_filename", done["video_filename"])
        put("start_datetime_user", done["start_datetime_user"])
        put("start_ts_unix_user_ms", done["start_ts_unix_user_ms"])
        put("timestamp_source", done["timestamp_source"])
    }
    return VideoUploadResponse(raw)
}

private fun CollectionsResource.multipartUpload(
    source: UploadSource,
    collectionName: String,
    subCollectionName: String,
    mode: String,
    modality: String,
    ttl: Int,
    options: VideoUploadOptions,
    handle: UploadHandle,
    onProgress: (UploadProgress) -> Unit,
): VideoUploadResponse {
    val sessionKey = multipartSessionKey(source, collectionName, subCollectionName, mode, modality, options)
    val owner = uploadSessionOwners.acquire(sessionKey)
        ?: throw ValidationFailed("multipart upload is already active for this session")
    return try {
        multipartUploadOwned(source, collectionName, subCollectionName, mode, modality, ttl, options, sessionKey, handle, onProgress)
    } finally {
        uploadSessionOwners.release(owner)
    }
}

private fun CollectionsResource.multipartUploadOwned(
    source: UploadSource,
    collectionName: String,
    subCollectionName: String,
    mode: String,
    modality: String,
    ttl: Int,
    options: VideoUploadOptions,
    sessionKey: String,
    handle: UploadHandle,
    onProgress: (UploadProgress) -> Unit,
): VideoUploadResponse {
    handle.ensureActive()
    val stored = options.sessionStore.load(sessionKey)?.let(MultipartSession::fromMap)
    if (!options.resume && stored != null) {
        multipartAbort(stored, handle)
        options.sessionStore.remove(sessionKey)
    }
    var active = if (options.resume) stored else null
    var resumed = active != null
    if (active == null) {
        active = multipartCreate(source, collectionName, subCollectionName, mode, modality, options, handle)
        options.sessionStore.save(sessionKey, active.toMap())
    }

    handle.ensureActive()
    var status = try {
        multipartStatus(active, handle)
    } catch (exc: ApiError) {
        if (exc.statusCode != 404) throw exc
        options.sessionStore.remove(sessionKey)
        active = multipartCreate(source, collectionName, subCollectionName, mode, modality, options, handle)
        options.sessionStore.save(sessionKey, active.toMap())
        resumed = false
        multipartStatus(active, handle)
    }
    if (status["status"]?.toString() == "completed") {
        if (status["size_bytes"].asLong() != source.contentLength || status["etag"]?.toString().orEmpty().isBlank()) {
            throw ApiError("completed multipart status does not match local upload contract")
        }
        handle.ensureActive()
        val done = uploadDone(
            active.key, source, collectionName, subCollectionName,
            mode, modality, options, handle,
        )
        options.sessionStore.remove(sessionKey)
        return multipartResponse(source, active, status["etag"]?.toString().orEmpty(), resumed = resumed, attempts = 0, done = done)
    }

    val remote = status["parts"].asObjectList()
    val numbers = remote.map { it["part_number"].asInt() }
    if (numbers.size != numbers.toSet().size || numbers.any { it !in 1..active.partCount }) {
        throw ApiError("multipart status returned duplicate or out-of-range parts")
    }
    val existing = remote.associateBy { it["part_number"].asInt() }
    val valid = mutableMapOf<Int, Map<String, Any?>>()
    for ((number, part) in existing) {
        val length = partLength(source.contentLength, active.partSize, number)
        if (part["size_bytes"].asLong() != length) continue
        val expected = active.partMd5[number] ?: md5Hex(source, (number - 1) * active.partSize, length)
        if (part["etag"]?.toString().orEmpty().lowercase() == expected.lowercase()) {
            valid[number] = part
            active.partMd5[number] = expected
        }
    }
    options.sessionStore.save(sessionKey, active.toMap())
    val missing = (1..active.partCount).filter { it !in valid }
    val progress = MultipartProgress(source.contentLength, handle, onProgress)
    valid.forEach { (number, part) -> progress.record(number, part["size_bytes"].asLong(), false) }
    if (valid.isNotEmpty()) progress.emit()

    var attempts = 0
    val partPermits = Semaphore(options.maxConcurrency)
    var futures = emptyList<java.util.concurrent.Future<UploadedPart>>()
    try {
        missing.chunked(minOf(32, options.maxConcurrency * 2)).forEach { batch ->
            handle.ensureActive()
            val signedParts = multipartSign(active, batch, ttl, handle)["parts"].asObjectList()
            val signed = signedParts.associateBy { it["part_number"].asInt() }
            if (signedParts.size != batch.size || signed.keys != batch.toSet()) {
                throw ApiError("multipart sign response did not match requested parts")
            }
            futures = batch.map { number ->
                uploadScheduler.submitData(java.util.concurrent.Callable {
                    partPermits.acquire()
                    try {
                        handle.ensureActive()
                        multipartPutOne(source, active, number, signed.getValue(number), ttl, options, handle) { item ->
                            progress.record(number, item.uploadedBytes)
                        }
                    } finally {
                        partPermits.release()
                    }
                })
            }
            val uploaded: List<UploadedPart> = try {
                val pending = futures.toMutableList()
                buildList<UploadedPart> {
                    while (pending.isNotEmpty()) {
                        handle.ensureActive()
                        val done = pending.firstOrNull { it.isDone }
                        if (done == null) {
                            Thread.sleep(5)
                        } else {
                            pending.remove(done)
                            add(done.get())
                        }
                    }
                }
            } catch (exc: Exception) {
                handle.stop()
                uploadAwaitTasks(futures)
                val cause = if (exc is ExecutionException) exc.cause as? Exception ?: exc else exc
                if (cause is InterruptedException) Thread.currentThread().interrupt()
                throw cause
            }
            uploaded.forEach { part ->
                attempts += part.attempts
                active.partMd5[part.number] = part.md5
                progress.record(part.number, part.size, false)
            }
            options.sessionStore.save(sessionKey, active.toMap())
            progress.emit()
        }
    } finally {
        if (handle.isStopped) uploadAwaitTasks(futures)
    }

    handle.ensureActive()
    status = multipartStatus(active, handle)
    val parts = multipartFinalParts(active, source.contentLength, status)
    val complete = http.requestUpload("POST", Routes.full(Routes.externalUploadMultipartComplete), handle, json = mapOf(
        "request_id" to active.requestId,
        "upload_id" to active.uploadId,
        "key" to active.key,
        "size_bytes" to source.contentLength,
        "parts" to parts,
    ))
    val etag = complete["etag"]?.toString().orEmpty()
    if (etag.isBlank()) throw ApiError("multipart complete response returned no ETag")
    handle.ensureActive()
    val done = uploadDone(
        active.key, source, collectionName, subCollectionName,
        mode, modality, options, handle,
    )
    options.sessionStore.remove(sessionKey)
    progress.complete()
    return multipartResponse(source, active, etag, resumed, attempts, done)
}

private fun CollectionsResource.multipartCreate(
    source: UploadSource,
    collection: String,
    stream: String,
    mode: String,
    modality: String,
    options: VideoUploadOptions,
    handle: UploadHandle,
): MultipartSession {
    val requestId = UUID.randomUUID().toString()
    val create = http.requestUpload("POST", Routes.full(Routes.externalUploadMultipartCreate), handle, json = mapOf(
        "request_id" to requestId,
        "mode" to mode,
        "group_name" to collection,
        "stream_name" to stream,
        "modality" to modality,
        "filename" to source.fileName,
        "content_type" to source.contentType,
        "size_bytes" to source.contentLength,
        "part_size_bytes" to options.partSizeBytes,
    ))
    val session = MultipartSession(
        requestId = create["request_id"]?.toString() ?: requestId,
        uploadId = create["upload_id"]?.toString().orEmpty(),
        key = create["key"]?.toString().orEmpty(),
        partCount = create["part_count"].asInt(),
        partSize = create["part_size_bytes"].asLong(),
    )
    val expected = partCount(source.contentLength, options.partSizeBytes).toInt()
    if (session.uploadId.isBlank() || session.key.isBlank() || session.partCount != expected || session.partSize != options.partSizeBytes) {
        throw ApiError("multipart create response does not match local upload contract")
    }
    return session
}

private fun CollectionsResource.multipartPutOne(
    source: UploadSource,
    session: MultipartSession,
    number: Int,
    signed: Map<String, Any?>,
    ttl: Int,
    options: VideoUploadOptions,
    handle: UploadHandle,
    onProgress: (UploadProgress) -> Unit,
): UploadedPart {
    val offset = (number - 1) * session.partSize
    val length = partLength(source.contentLength, session.partSize, number)
    var item = signed
    var last: Exception? = null
    repeat(options.maxPartAttempts) { idx ->
        handle.ensureActive()
        try {
            val headers = (item["headers"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
            val result = uploadAwait(
                source,
                item["url"]?.toString().orEmpty(),
                item["method"]?.toString() ?: "PUT",
                offset,
                length,
                headers,
                options.partTimeoutSeconds * 1000,
                handle,
                onProgress,
                idx + 1,
            )
            val expected = result.localMd5.ifBlank { md5Hex(source, offset, length) }
            if (result.etag.isBlank() || result.etag.lowercase() != expected.lowercase()) {
                throw ApiError("multipart part ETag mismatch", body = mapOf("part_number" to number))
            }
            return UploadedPart(number, result.etag, length, expected, idx + 1)
        } catch (exc: Exception) {
            handle.ensureActive()
            last = exc
            if (exc is SignedUploadFailure && exc.sentBytes == length && exc.localMd5.isNotBlank()) {
                val found = multipartReconcilePart(session, number, length, exc.localMd5, handle)
                if (found != null) return UploadedPart(number, found, length, exc.localMd5, idx + 1)
            }
            val retry = when {
                exc is ApiError && exc.statusCode == 403 && idx + 1 < options.maxPartAttempts -> {
                    val refreshed = multipartSign(session, listOf(number), ttl, handle)["parts"].asObjectList()
                    if (refreshed.size != 1 || refreshed.single()["part_number"].asInt() != number) {
                        throw ApiError("multipart URL refresh returned no matching part")
                    }
                    item = refreshed.single()
                    true
                }
                exc is ApiError -> exc.statusCode in PART_RETRY_CODES
                exc is IOException -> true
                else -> false
            }
            if (!retry || idx + 1 >= options.maxPartAttempts) throw exc
            uploadRetrySleep(handle, idx)
        }
    }
    throw last ?: ApiError("multipart part attempts exhausted")
}

private fun CollectionsResource.multipartReconcilePart(
    session: MultipartSession,
    number: Int,
    length: Long,
    md5: String,
    handle: UploadHandle,
): String? = try {
    multipartStatus(session, handle)["parts"].asObjectList().firstOrNull {
        it["part_number"].asInt() == number && it["size_bytes"].asLong() == length &&
            it["etag"]?.toString().orEmpty().lowercase() == md5.lowercase()
    }?.get("etag")?.toString()
} catch (_: ApiError) {
    handle.ensureActive()
    null
}

private fun multipartFinalParts(session: MultipartSession, size: Long, status: Map<String, Any?>): List<Map<String, Any?>> {
    val remote = status["parts"].asObjectList().sortedBy { it["part_number"].asInt() }
    if (remote.map { it["part_number"].asInt() } != (1..session.partCount).toList()) {
        throw ApiError("multipart status is missing, duplicate, or unsorted parts")
    }
    return remote.map { part ->
        val number = part["part_number"].asInt()
        val expectedSize = partLength(size, session.partSize, number)
        val etag = part["etag"]?.toString().orEmpty()
        val expectedMd5 = session.partMd5[number].orEmpty()
        if (part["size_bytes"].asLong() != expectedSize || etag.isBlank() || expectedMd5.isBlank() || etag.lowercase() != expectedMd5.lowercase()) {
            throw ApiError("multipart status returned an invalid part", body = mapOf("part_number" to number))
        }
        mapOf("part_number" to number, "etag" to etag)
    }
}

private fun CollectionsResource.multipartAbort(session: MultipartSession, handle: UploadHandle) =
    http.requestUpload("POST", Routes.full(Routes.externalUploadMultipartAbort), handle, json = mapOf(
        "request_id" to session.requestId, "upload_id" to session.uploadId, "key" to session.key,
    ))

private fun multipartResponse(
    source: UploadSource,
    session: MultipartSession,
    etag: String,
    resumed: Boolean,
    attempts: Int,
    done: Map<String, Any?>,
) = VideoUploadResponse(mapOf(
    "filename" to source.fileName,
    "size_bytes" to source.contentLength,
    "status_code" to 200,
    "uploaded" to true,
    "upload_strategy" to "multipart",
    "upload_id" to session.uploadId,
    "key" to session.key,
    "etag" to etag,
    "part_size_bytes" to session.partSize,
    "part_count" to session.partCount,
    "parts_uploaded" to session.partCount,
    "resumed" to resumed,
    "attempt_count" to attempts,
    "upload_done" to done,
    "dest_path" to done["dest_path"]?.toString().orEmpty(),
    "video_filename" to done["video_filename"],
    "start_datetime_user" to done["start_datetime_user"],
    "start_ts_unix_user_ms" to done["start_ts_unix_user_ms"],
    "timestamp_source" to done["timestamp_source"],
))

private fun CollectionsResource.uploadDone(
    key: String,
    source: UploadSource,
    collection: String,
    stream: String,
    mode: String,
    modality: String,
    options: VideoUploadOptions,
    handle: UploadHandle,
) = http.requestUpload(
    "POST",
    Routes.full(Routes.externalUploadDone),
    handle,
    params = linkedMapOf<String, Any?>(
        "key" to key,
        "mode" to mode,
        "group_name" to collection,
        "stream_name" to stream,
        "modality" to modality,
        "filename" to source.fileName,
        "re_process" to options.reProcess,
    ).apply {
        val videoName = options.videoFilename
            ?: if (options.startDatetimeUser != null) source.fileName else null
        if (videoName != null) put("video_filename", videoName)
        if (options.metadataText != null) put("metadata_text", options.metadataText)
        if (options.metadataTags != null) put("metadata_tags", options.metadataTags)
        if (options.startDatetimeUser != null) {
            put("start_datetime_user", options.startDatetimeUser)
        }
    },
)

private fun CollectionsResource.multipartStatus(session: MultipartSession, handle: UploadHandle) =
    http.requestUpload("GET", Routes.full(Routes.externalUploadMultipartStatus), handle, params = mapOf("request_id" to session.requestId, "upload_id" to session.uploadId, "key" to session.key))

private fun CollectionsResource.multipartSign(session: MultipartSession, numbers: List<Int>, ttl: Int, handle: UploadHandle) =
    http.requestUpload("POST", Routes.full(Routes.externalUploadMultipartSignParts), handle, json = mapOf(
        "request_id" to session.requestId, "upload_id" to session.uploadId, "key" to session.key, "part_numbers" to numbers, "ttl" to ttl,
    ))

private fun CollectionsResource.uploadParams(source: UploadSource, collection: String, stream: String, mode: String, modality: String, ttl: Int) =
    mapOf("mode" to mode, "group_name" to collection, "stream_name" to stream, "modality" to modality, "filename" to source.fileName, "ttl" to ttl)

private fun CollectionsResource.uploadAwait(
    source: UploadSource,
    url: String,
    method: String,
    offset: Long = 0,
    length: Long = source.contentLength,
    headers: Map<String, String> = emptyMap(),
    timeoutMillis: Long? = null,
    handle: UploadHandle,
    onProgress: (UploadProgress) -> Unit = {},
    attempt: Int = 1,
): SignedUploadResult {
    handle.ensureActive()
    val result = AtomicReference<SignedUploadResult>()
    val error = AtomicReference<Exception>()
    val latch = CountDownLatch(1)
    val success = { value: SignedUploadResult -> result.set(value); latch.countDown() }
    val failure = { value: Exception -> error.set(value); latch.countDown() }
    if (signedUploads is OkHttpSignedUploadTransport) {
        signedUploads.enqueueDiagnostic(
            source, url, method, offset, length, headers, timeoutMillis, handle, onProgress, success, failure, attempt
        )
    } else {
        signedUploads.enqueue(
            source, url, method, offset, length, headers, timeoutMillis, handle, onProgress, success, failure
        )
    }
    val waitMillis = timeoutMillis?.coerceAtMost(Long.MAX_VALUE - 5_000)?.plus(5_000) ?: TimeUnit.HOURS.toMillis(24)
    val completed = try {
        latch.await(waitMillis, TimeUnit.MILLISECONDS)
    } catch (exc: InterruptedException) {
        handle.stop()
        handle.awaitQuiescence()
        Thread.currentThread().interrupt()
        throw ApiError("signed upload interrupted").also { it.initCause(exc) }
    }
    if (!completed) {
        handle.stop()
        handle.awaitQuiescence()
        throw ApiError("signed upload timed out")
    }
    handle.ensureActive()
    error.get()?.let { throw it }
    return result.get() ?: throw ApiError("signed upload returned no result")
}

private fun partLength(size: Long, partSize: Long, number: Int): Long = minOf(partSize, size - (number - 1) * partSize)

private fun partCount(size: Long, partSize: Long): Long = if (size <= 0) 1 else 1 + (size - 1) / partSize

private fun CollectionsResource.multipartSessionKey(
    source: UploadSource,
    collection: String,
    stream: String,
    mode: String,
    modality: String,
    options: VideoUploadOptions,
): String = strSha256(VmodalJson.stringify(listOf(
    "v2",
    http.cfg.normalizedBaseUrl,
    http.cfg.normalizedUserId,
    source.sourceId,
    source.versionTag,
    source.fileName,
    source.contentType,
    source.contentLength,
    options.partSizeBytes,
    mode,
    collection,
    stream,
    modality,
)))

private fun uploadRetrySleep(handle: UploadHandle, idx: Int) {
    var left = minOf(30_000L, 250L shl minOf(idx, 6))
    while (left > 0) {
        handle.ensureActive()
        val wait = minOf(100L, left)
        Thread.sleep(wait)
        left -= wait
    }
}

private data class UploadedPart(val number: Int, val etag: String, val size: Long, val md5: String, val attempts: Int)

internal class MultipartProgress(
    private val total: Long,
    private val handle: UploadHandle,
    private val observer: (UploadProgress) -> Unit,
) {
    private val lock = Any()
    private val high = mutableMapOf<Int, Long>()
    private val deliveryOwner = AtomicReference<Thread?>()
    private val deliveryDepth = ThreadLocal<Boolean>()
    private var sent = 0L

    fun record(part: Int, value: Long, notify: Boolean = true) {
        synchronized(lock) {
            val old = high[part] ?: 0
            val next = maxOf(old, value.coerceAtLeast(0))
            if (next > old) {
                high[part] = next
                sent += minOf(next - old, total - sent)
            }
        }
        if (notify) deliver()
    }

    fun emit() = deliver()

    fun complete() {
        synchronized(lock) { sent = total }
        deliver()
    }

    private fun deliver() {
        if (handle.isStopped) return
        if (deliveryDepth.get() == true) {
            if (!handle.isStopped) observer(snapshot())
            return
        }
        val thread = Thread.currentThread()
        while (!deliveryOwner.compareAndSet(null, thread)) {
            if (handle.isStopped) return
            LockSupport.parkNanos(100_000)
        }
        try {
            if (handle.isStopped) return
            deliveryDepth.set(true)
            observer(snapshot())
        } finally {
            deliveryDepth.remove()
            deliveryOwner.set(null)
        }
    }

    private fun snapshot(): UploadProgress = synchronized(lock) { UploadProgress(sent, total) }
}

private fun uploadAwaitTasks(futures: List<java.util.concurrent.Future<UploadedPart>>) {
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    futures.forEach { future ->
        val left = end - System.nanoTime()
        if (left <= 0) return
        try {
            future.get(left, TimeUnit.NANOSECONDS)
        } catch (_: ExecutionException) {
        } catch (_: CancellationException) {
        } catch (_: java.util.concurrent.TimeoutException) {
            return
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
    }
}

private data class MultipartSession(
    val requestId: String,
    val uploadId: String,
    val key: String,
    val partCount: Int,
    val partSize: Long,
    val partMd5: MutableMap<Int, String> = mutableMapOf(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "version" to 1,
        "request_id" to requestId,
        "upload_id" to uploadId,
        "key" to key,
        "part_count" to partCount,
        "part_size_bytes" to partSize,
        "part_md5" to partMd5.mapKeys { it.key.toString() },
    )

    companion object {
        fun fromMap(raw: Map<String, Any?>): MultipartSession {
            val md5 = (raw["part_md5"] as? Map<*, *>)?.entries?.associate {
                it.key.toString().toInt() to it.value.toString()
            }?.toMutableMap() ?: mutableMapOf()
            val session = MultipartSession(
                requestId = raw["request_id"]?.toString().orEmpty(),
                uploadId = raw["upload_id"]?.toString().orEmpty(),
                key = raw["key"]?.toString().orEmpty(),
                partCount = raw["part_count"].asInt(),
                partSize = raw["part_size_bytes"].asLong(),
                partMd5 = md5,
            )
            if (raw["version"].asInt() != 1 || session.requestId.isBlank() || session.uploadId.isBlank() ||
                session.key.isBlank() || session.partCount <= 0 || session.partSize <= 0
            ) throw ApiError("multipart upload checkpoint is invalid")
            return session
        }
    }
}

internal val uploadSessionOwners = UploadSessionOwnerRegistry()
private val PART_RETRY_CODES = setOf(408, 429, 500, 502, 503, 504)

internal data class UploadSessionOwner(val key: String, val generation: Long)

internal class UploadSessionOwnerRegistry {
    private val next = AtomicLong()
    private val owners = ConcurrentHashMap<String, UploadSessionOwner>()

    val size: Int get() = owners.size

    fun acquire(key: String): UploadSessionOwner? {
        val owner = UploadSessionOwner(key, next.incrementAndGet())
        return if (owners.putIfAbsent(key, owner) == null) owner else null
    }

    fun release(owner: UploadSessionOwner): Boolean = owners.remove(owner.key, owner)
}

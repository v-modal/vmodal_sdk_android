package com.vmodal.sdk

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Coroutine-first view of an existing [Client].
 *
 * Each operation has the same request model, validation, retry policy, response model, and
 * [SdkError] types as its blocking counterpart. Cancellation is propagated to transports that
 * implement [CancellableVmodalTransport]. Legacy transports run on [fallbackDispatcher]; their
 * result is not delivered after cancellation, although arbitrary blocking code may not stop
 * immediately. This facade owns no coroutine scope or Android lifecycle object.
 */
class CoroutineClient internal constructor(
    client: Client,
    fallbackDispatcher: CoroutineDispatcher,
) {
    internal val http = client.coroutineHttp()
    private val collectionsResource = if (http === client.http) {
        client.collections
    } else {
        CollectionsResource(http, client.collections.signedUploads)
    }

    /** Suspending authentication and availability operations. */
    val auth = CoroutineAuthResource(http, fallbackDispatcher)
    /** Suspending collection and upload operations. */
    val collections = CoroutineCollectionsResource(collectionsResource, fallbackDispatcher)
    /** Suspending typed multimodal search operations. */
    val searches = CoroutineSearchesResource(http, fallbackDispatcher)
    /** Suspending index lifecycle operations. */
    val indexes = CoroutineIndexesResource(http, fallbackDispatcher)
    /** Suspending image URL and bounded-byte operations. */
    val images = CoroutineImagesResource(http, fallbackDispatcher)
    /** Suspending administrative reporting operations. */
    val admin = CoroutineAdminResource(http, fallbackDispatcher)
    /** Suspending object-storage signing operations. */
    val r2 = CoroutineR2Resource(http, fallbackDispatcher)

    /** Suspending counterpart of [Client.health]. */
    suspend fun health(): HealthResponse = auth.health()

    /** Suspending counterpart of [Client.authCheck]. */
    suspend fun authCheck(userId: String = ""): Boolean = auth.authCheck(userId)
}

/**
 * Coroutine counterpart of [AuthResource]. Cancellable transports are cancelled with the caller;
 * safe reads retain the blocking retry policy and failures retain their typed [SdkError].
 */
class CoroutineAuthResource internal constructor(
    private val http: VmodalHttp,
    private val fallbackDispatcher: CoroutineDispatcher,
) {
    /** Suspending counterpart of [AuthResource.health]. */
    suspend fun health(): HealthResponse = HealthResponse(
        http.requestSuspend("GET", Routes.full(Routes.Endpoints.health), fallbackDispatcher = fallbackDispatcher)
    )

    /** Suspending counterpart of [AuthResource.authCheck]. */
    suspend fun authCheck(userId: String = ""): Boolean {
        val cfg = if (userId.isBlank()) http.cfg else http.cfg.copy(userId = userId.trim())
        CoroutineAuthResource(VmodalHttp(cfg, http.transport), fallbackDispatcher).health()
        return true
    }

    /** Suspending counterpart of [AuthResource.me]. */
    suspend fun me(): UserProfile = UserProfile(
        http.requestUsersSuspend(
            "GET",
            Routes.usersFull(Routes.UsersEndpoints.authMe),
            fallbackDispatcher = fallbackDispatcher,
        )
    )
}

/**
 * Coroutine counterpart of [SearchesResource]. Validation uses [SearchRequest], mutations are not
 * retried, cancellation reaches cancellable transports, and typed SDK failures propagate unchanged.
 */
class CoroutineSearchesResource internal constructor(
    private val http: VmodalHttp,
    private val fallbackDispatcher: CoroutineDispatcher,
) {
    /** Suspending counterpart of [SearchesResource.searchVideo]. */
    suspend fun searchVideo(request: SearchRequest): SearchResponse {
        request.validate()
        return SearchResponse(
            http.requestSuspend(
                "POST",
                Routes.full(Routes.Endpoints.searchClient),
                json = request.toMap(),
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending convenience counterpart of [SearchesResource.searchVideo]. */
    suspend fun searchVideo(
        queryText: String = "",
        queryMetadata: Map<String, Any?>? = null,
        imageQuery: String? = null,
        mode: String = "vid_file",
        groupName: String = "agroup",
        streamName: String = "astream",
        searchSources: List<String> = listOf("ocr", "asr", "image"),
        searchCombineMode: String = "union",
        startDate: String? = null,
        endDate: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        textEmbScoreMin: Double = 0.90,
        imageEmbScoreMin: Double = 1.5,
        versionLancedb: Int? = null,
    ): SearchResponse = searchVideo(
        SearchRequest(
            queryText, queryMetadata, imageQuery, mode, groupName, streamName, searchSources,
            searchCombineMode, startDate, endDate, offset, limit, textEmbScoreMin,
            imageEmbScoreMin, versionLancedb,
        )
    )
}

/**
 * Coroutine counterpart of [CollectionsResource]. It retains blocking validation, request models,
 * mutation no-retry behavior, typed errors, and disabled-operation behavior. Gateway requests use
 * cancellable transport suspension; signed video cancellation also cancels its [UploadHandle].
 */
class CoroutineCollectionsResource internal constructor(
    private val resource: CollectionsResource,
    private val fallbackDispatcher: CoroutineDispatcher,
) {
    private val http = resource.http

    /** Suspending counterpart of [CollectionsResource.listGroups]. */
    suspend fun listGroups(mode: String? = null): GroupsResponse {
        val params = if (mode == null) emptyMap() else mapOf("mode" to mode)
        return GroupsResponse(
            http.requestSuspend(
                "GET",
                Routes.full(Routes.Endpoints.groups),
                params = params,
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending counterpart of [CollectionsResource.uploadFile]. */
    suspend fun uploadFile(
        part: VmodalFilePart,
        groupName: String = "",
        mode: String = "vid_file",
        streamName: String = "astream",
        description: String = "",
        tag: List<String> = emptyList(),
    ): UploadResponse {
        val form = linkedMapOf<String, Any?>(
            "mode" to mode,
            "group_name" to groupName,
            "stream_name" to streamName,
            "description" to description,
        )
        if (tag.isNotEmpty()) form["tag"] = tag
        return UploadResponse(
            http.requestSuspend(
                "POST",
                Routes.full(Routes.Endpoints.upload),
                data = form,
                files = listOf(part),
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending file convenience counterpart of [CollectionsResource.uploadFile]. */
    suspend fun uploadFile(
        file: File,
        groupName: String = "",
        mode: String = "vid_file",
        streamName: String = "astream",
        description: String = "",
        tag: List<String> = emptyList(),
    ): UploadResponse = uploadFile(filePart("file", file), groupName, mode, streamName, description, tag)

    /** Suspending counterpart of disabled [CollectionsResource.uploadFolder]. */
    suspend fun uploadFolder(): Nothing = resource.uploadFolder()

    /** Suspending counterpart of [CollectionsResource.uploadMetadataJsonl]. */
    suspend fun uploadMetadataJsonl(
        part: VmodalFilePart,
        mode: String = "img_file",
        groupName: String = "",
        streamName: String = "",
        writeMode: String = "append",
        allowOverlap: Boolean = false,
    ): MetadataParquetUploadResponse {
        val form = linkedMapOf<String, Any?>(
            "mode" to mode,
            "group_name" to groupName,
            "stream_name" to streamName,
            "write_mode" to writeMode,
            "allow_overlap" to allowOverlap.toString(),
        )
        if (http.cfg.normalizedMode == "direct") form["user_id"] = http.cfg.normalizedUserId
        val body = try {
            http.requestSuspend(
                "POST",
                Routes.full(Routes.Endpoints.uploadMetadataJsonl),
                data = form,
                files = listOf(part),
                fallbackDispatcher = fallbackDispatcher,
            )
        } catch (error: ApiError) {
            if (error.statusCode != 404) throw error
            http.requestSuspend(
                "POST",
                Routes.Endpoints.uploadMetadataItemParquetInternal,
                data = form,
                files = listOf(part),
                fallbackDispatcher = fallbackDispatcher,
            )
        }
        return MetadataParquetUploadResponse(body)
    }

    /** Suspending counterpart of [CollectionsResource.addAssets]. */
    suspend fun addAssets(
        collectionId: String,
        assetIds: List<String>,
        mode: String,
        groupName: String,
        streamName: String = "astream",
    ): CollectionAddAssetsResponse {
        val req = CollectionAddAssetsRequest(collectionId, assetIds, mode, groupName, streamName).also { it.validate() }
        val path = Routes.Endpoints.collectionAddAssets.replace("{collection_id}", collectionId)
        return CollectionAddAssetsResponse(
            http.requestSuspend(
                "POST",
                Routes.full(path),
                json = req.toMap(),
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending counterpart of [CollectionsResource.updateDescription]. */
    suspend fun updateDescription(
        groupName: String,
        mode: String,
        streamName: String,
        filenameSanitized: String,
        description: String? = null,
        tag: List<String>? = null,
    ): CollectionDescriptionUpdateResponse {
        val form = linkedMapOf<String, Any?>(
            "group_name" to groupName,
            "mode" to mode,
            "stream_name" to streamName,
            "filename_sanitized" to filenameSanitized,
        )
        if (description != null) form["description"] = description
        if (tag != null) form["tag"] = tag
        return CollectionDescriptionUpdateResponse(
            http.requestSuspend(
                "POST",
                Routes.full(Routes.Endpoints.collectionDescriptionUpdate),
                data = form,
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending counterpart of [CollectionsResource.delete]. */
    suspend fun delete(
        groupName: String,
        mode: String,
        scope: String = "all",
        dryRun: Boolean = false,
        confirm: Boolean = false,
    ): DeleteCollectionResponse {
        val req = DeleteCollectionRequest(groupName, mode, scope, dryRun, confirm).also { it.validate() }
        return DeleteCollectionResponse(
            http.requestSuspend(
                "DELETE",
                Routes.full(Routes.Endpoints.collectionDelete),
                json = req.toMap(),
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending counterpart of disabled [CollectionsResource.create]. */
    suspend fun create(): Nothing = resource.create()

    /** Suspending counterpart of disabled [CollectionsResource.edit]. */
    suspend fun edit(): Nothing = resource.edit()

    /** Suspending counterpart of disabled [CollectionsResource.autoIndexGet]. */
    suspend fun autoIndexGet(): Nothing = resource.autoIndexGet()

    /** Suspending counterpart of disabled [CollectionsResource.autoIndexSet]. */
    suspend fun autoIndexSet(): Nothing = resource.autoIndexSet()

    /**
     * Suspending result counterpart of `CollectionsResource.videoUploadAsync`.
     *
     * The existing signed-upload orchestration and retry policy are retained. Caller cancellation
     * cancels the shared [UploadHandle], failures retain their original SDK type, and no scope is
     * created or retained by the facade.
     */
    suspend fun videoUpload(
        source: UploadSource,
        collectionName: String,
        subCollectionName: String,
        mode: String = "vid_file",
        modality: String = "vid_raw",
        ttl: Int = 12600,
        options: VideoUploadOptions = VideoUploadOptions(),
        onProgress: (UploadProgress) -> Unit = {},
    ): VideoUploadResponse = suspendCancellableCoroutine { continuation ->
        val done = AtomicBoolean(false)
        val handleRef = AtomicReference<UploadHandle?>()
        continuation.invokeOnCancellation {
            if (done.compareAndSet(false, true)) handleRef.get()?.cancel()
        }
        try {
            val handle = resource.videoUploadAsync(
                source,
                collectionName,
                subCollectionName,
                mode,
                modality,
                ttl,
                options,
                onProgress,
                onSuccess = { response ->
                    if (done.compareAndSet(false, true)) continuation.resume(response)
                },
                onFailure = { error ->
                    if (done.compareAndSet(false, true)) continuation.resumeWithException(error)
                },
            )
            handleRef.set(handle)
            if (!continuation.isActive && done.get()) handle.cancel()
        } catch (error: Throwable) {
            if (done.compareAndSet(false, true)) continuation.resumeWithException(error)
        }
    }

    /**
     * Cold Flow counterpart of `CollectionsResource.videoUploadAsync`.
     *
     * Each collection starts one independent upload. Progress is bounded and may be conflated;
     * [VideoUploadEvent.Completed] is delivered once and is never dropped. Collector cancellation
     * cancels the shared [UploadHandle] and all active signed-part calls.
     */
    fun videoUploadEvents(
        source: UploadSource,
        collectionName: String,
        subCollectionName: String,
        mode: String = "vid_file",
        modality: String = "vid_raw",
        ttl: Int = 12600,
        options: VideoUploadOptions = VideoUploadOptions(),
    ): Flow<VideoUploadEvent> = resource.videoUploadEvents(
        source,
        collectionName,
        subCollectionName,
        mode,
        modality,
        ttl,
        options,
    )
}

/**
 * Coroutine counterpart of [IndexesResource]. Request validation and typed errors are shared with
 * the blocking models; safe status/list calls retry, while create/delete mutations do not.
 */
class CoroutineIndexesResource internal constructor(
    private val http: VmodalHttp,
    private val fallbackDispatcher: CoroutineDispatcher,
) {
    /** Suspending counterpart of [IndexesResource.jobsList]. */
    suspend fun jobsList(
        status: String? = null,
        mode: String? = null,
        groupName: String? = null,
        limit: Int = 200,
    ): IndexationJobsListResponse {
        if (limit !in 1..1000) throw ValidationFailed("limit must be between 1 and 1000")
        val params = linkedMapOf<String, Any?>("limit" to limit)
        if (status != null) params["status"] = status
        if (mode != null) params["mode"] = mode
        if (groupName != null) params["group_name"] = groupName
        return IndexationJobsListResponse(
            http.requestSuspend(
                "GET",
                Routes.full(Routes.Endpoints.indexationJobs),
                params = params,
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending counterpart of [IndexesResource.createIndex]. */
    suspend fun createIndex(request: IndexationSubmitRequest): IndexationSubmitResponse =
        IndexationSubmitResponse(
            http.requestSuspend(
                "POST",
                Routes.full(Routes.Endpoints.indexationSubmit),
                json = request.also { it.validate() }.toMap(),
                fallbackDispatcher = fallbackDispatcher,
            )
        )

    /** Suspending convenience counterpart of [IndexesResource.createIndex]. */
    suspend fun createIndex(
        mode: String = "",
        groupName: String = "",
        indexType: String? = null,
        modality: String? = null,
        streamName: String? = null,
        insertMode: String = "append",
        createIndex: Boolean = true,
        version: String = "new_version",
        startDate: String? = null,
        endDate: String? = null,
        embeddingModel: String? = null,
        reProcess: Boolean = false,
        dryRun: Boolean = false,
    ): IndexationSubmitResponse = createIndex(
        IndexationSubmitRequest(
            mode, groupName, streamName, indexType, modality, insertMode, createIndex, version,
            startDate, endDate, embeddingModel, reProcess, dryRun,
        )
    )

    /** Suspending counterpart of [IndexesResource.indexStatus]. */
    suspend fun indexStatus(jobId: String = ""): IndexationStatusResponse {
        val clean = strRequired(jobId, "job_id")
        val path = Routes.Endpoints.indexationStatus.replace("{job_id}", clean)
        return try {
            IndexationStatusResponse(
                http.requestSuspend("GET", Routes.full(path), fallbackDispatcher = fallbackDispatcher)
            )
        } catch (error: ApiError) {
            if (error.statusCode != 404) throw error
            val row = jobsList(limit = 1000).raw["data"].asObjectList()
                .firstOrNull { it["job_id"]?.toString() == clean } ?: throw error
            IndexationStatusResponse(row)
        }
    }

    /** Suspending counterpart of [IndexesResource.deleteIndex]. */
    suspend fun deleteIndex(request: IndexationDeleteRequest): IndexationDeleteResponse =
        IndexationDeleteResponse(
            http.requestSuspend(
                "DELETE",
                Routes.full(Routes.Endpoints.indexationDelete),
                json = request.also { it.validate() }.toMap(),
                fallbackDispatcher = fallbackDispatcher,
            )
        )

    /** Suspending convenience counterpart of [IndexesResource.deleteIndex]. */
    suspend fun deleteIndex(
        mode: String = "",
        groupName: String = "",
        version: String = "",
        modality: String? = null,
        dryRun: Boolean = false,
        confirm: Boolean = false,
    ): IndexationDeleteResponse = deleteIndex(
        IndexationDeleteRequest(mode, groupName, version, modality, dryRun, confirm)
    )

    /** Suspending counterpart of disabled [IndexesResource.embeddingModels]. */
    suspend fun embeddingModels(): Nothing = throw FeatureDisabled("embedding models endpoint is disabled on server")
}

/**
 * Coroutine counterpart of [ImagesResource]. Mutations are not retried, response limits remain
 * enforced, cancellable transports are cancelled with the caller, and typed errors are preserved.
 */
class CoroutineImagesResource internal constructor(
    private val http: VmodalHttp,
    private val fallbackDispatcher: CoroutineDispatcher,
) {
    /** Suspending counterpart of [ImagesResource.getUrl]. */
    suspend fun getUrl(
        mode: String,
        groupName: String,
        modality: String,
        filename: String,
        streamName: String = "astream",
        tsUnix13digits: Any? = null,
        userid: String? = null,
    ): ImageUrlResponse {
        val data = linkedMapOf<String, Any?>(
            "mode" to mode,
            "group_name" to groupName,
            "modality" to modality,
            "stream_name" to streamName,
            "filename" to filename,
        )
        if (tsUnix13digits != null) data["ts_unix_13digits"] = tsUnix13digits.toString()
        if (http.cfg.normalizedMode == "direct" && !userid.isNullOrBlank()) data["userid"] = userid
        val raw = http.requestSuspend(
                "POST",
                Routes.full(Routes.Endpoints.imageGetUrl),
                json = data,
                fallbackDispatcher = fallbackDispatcher,
            )
        return ImageUrlResponse(mapAbsoluteImageUrl(raw, http.cfg.normalizedBaseUrl))
    }

    /** Suspending counterpart of [ImagesResource.getUrlBulk]. */
    suspend fun getUrlBulk(
        records: List<Map<String, Any?>>,
        userid: String? = null,
    ): ImageUrlBulkResponse {
        val safeRecords = if (http.cfg.normalizedMode == "direct") records else records.map { it - "userid" - "user_id" }
        val data = linkedMapOf<String, Any?>("records" to safeRecords)
        if (http.cfg.normalizedMode == "direct" && !userid.isNullOrBlank()) data["userid"] = userid
        val raw = http.requestSuspend(
                "POST",
                Routes.full(Routes.Endpoints.imageGetUrlBulk),
                json = data,
                fallbackDispatcher = fallbackDispatcher,
            )
        return ImageUrlBulkResponse(mapAbsoluteImageUrls(raw, http.cfg.normalizedBaseUrl))
    }

    /** Suspending bounded-byte counterpart of [ImagesResource.getImageFromUrl]. */
    suspend fun getImageFromUrl(urlPreSigned: String, userid: String? = null): ByteArray {
        val data = linkedMapOf<String, Any?>("url_pre_signed" to urlPreSigned)
        if (http.cfg.normalizedMode == "direct" && !userid.isNullOrBlank()) data["userid"] = userid
        return http.requestBytesSuspend(
            "POST",
            Routes.full(Routes.Endpoints.imageGetImage),
            json = data,
            fallbackDispatcher = fallbackDispatcher,
        )
    }

    /** Suspending counterpart of [ImagesResource.getImageBulkFromUrls]. */
    suspend fun getImageBulkFromUrls(
        urls: List<String>,
        userid: String? = null,
    ): ImageGetBulkResponse {
        val data = linkedMapOf<String, Any?>("urls" to urls)
        if (http.cfg.normalizedMode == "direct" && !userid.isNullOrBlank()) data["userid"] = userid
        return ImageGetBulkResponse(
            http.requestSuspend(
                "POST",
                Routes.full(Routes.Endpoints.imageGetImageBulk),
                json = data,
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }
}

/**
 * Coroutine counterpart of [AdminResource]. Safe reads retain retry behavior, cancellable
 * transports are cancelled with the caller, and all typed SDK errors propagate unchanged.
 */
class CoroutineAdminResource internal constructor(
    private val http: VmodalHttp,
    private val fallbackDispatcher: CoroutineDispatcher,
) {
    /** Suspending counterpart of [AdminResource.userStats]. */
    suspend fun userStats(): AdminUserStatsResponse = AdminUserStatsResponse(
        http.requestSuspend(
            "GET",
            Routes.full(Routes.Endpoints.adminUserStats),
            fallbackDispatcher = fallbackDispatcher,
        )
    )

    /** Suspending counterpart of [AdminResource.usage]. */
    suspend fun usage(date: String = ""): UsageUserDetail {
        val params = if (date.isBlank()) emptyMap() else mapOf("date" to date.trim())
        return UsageUserDetail(
            http.requestUsersSuspend(
                "GET",
                Routes.usersFull(Routes.UsersEndpoints.adminUsage),
                params = params,
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending counterpart of [AdminResource.cacheStats]. */
    suspend fun cacheStats(): CacheStats = CacheStats(
        http.requestUsersSuspend(
            "GET",
            Routes.usersFull(Routes.UsersEndpoints.adminCacheStats),
            fallbackDispatcher = fallbackDispatcher,
        )
    )
}

/**
 * Coroutine counterpart of [R2Resource]. Safe presign reads retain retries, mutations do not retry,
 * cancellation reaches cancellable transports, and typed SDK failures propagate unchanged.
 */
class CoroutineR2Resource internal constructor(
    private val http: VmodalHttp,
    private val fallbackDispatcher: CoroutineDispatcher,
) {
    /** Suspending counterpart of [R2Resource.presignUploadFile]. */
    suspend fun presignUploadFile(
        mode: String,
        groupName: String,
        streamName: String,
        modality: String,
        filename: String,
        expiresIn: Int = 900,
    ): PresignedUploadResponse {
        val params = mapOf(
            "mode" to mode,
            "group_name" to groupName,
            "stream_name" to streamName,
            "modality" to modality,
            "filename" to filename,
            "expires_in" to expiresIn,
        )
        return PresignedUploadResponse(
            http.requestUsersSuspend(
                "GET",
                Routes.usersFull(Routes.UsersEndpoints.r2UploadFile),
                params = params,
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }

    /** Suspending counterpart of [R2Resource.presignUploadFolderVideo]. */
    suspend fun presignUploadFolderVideo(
        mode: String,
        groupName: String,
        streamName: String,
        filenames: List<String>,
        expiresIn: Int = 900,
    ): PresignedFolderResponse {
        val body = mapOf(
            "mode" to mode,
            "group_name" to groupName,
            "stream_name" to streamName,
            "filenames" to filenames,
            "expires_in" to expiresIn,
        )
        return PresignedFolderResponse(
            http.requestUsersSuspend(
                "POST",
                Routes.usersFull(Routes.UsersEndpoints.r2UploadFolderVideo),
                json = body,
                fallbackDispatcher = fallbackDispatcher,
            )
        )
    }
}

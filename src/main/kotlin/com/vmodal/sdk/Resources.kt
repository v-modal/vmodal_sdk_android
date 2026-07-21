package com.vmodal.sdk

import java.io.File

/** Authentication and availability operations. All methods block. */
class AuthResource(internal val http: VmodalHttp) {
    /** Returns typed availability information. */
    fun health(): HealthResponse = HealthResponse(http.request("GET", Routes.full(Routes.Endpoints.health)))

    /** Returns true when authenticated access succeeds. */
    fun authCheck(userId: String = ""): Boolean {
        val cfg = if (userId.isBlank()) {
            http.cfg
        } else {
            http.cfg.copy(userId = userId.trim()).withDiagnostics(http.cfg.diagnostics)
        }
        AuthResource(VmodalHttp(cfg, http.transport)).health()
        return true
    }

    /** Resolves the credential owner's profile. */
    fun me(): UserProfile = UserProfile(http.requestUsers("GET", Routes.usersFull(Routes.UsersEndpoints.authMe)))
}

/** Typed video search operations. */
class SearchesResource(private val http: VmodalHttp) {
    /** Validates and executes [request]. This method blocks. */
    fun searchVideo(request: SearchRequest): SearchResponse {
        request.validate()
        return SearchResponse(http.request("POST", Routes.full(Routes.Endpoints.searchClient), json = request.toMap()))
    }

    /** Convenience overload that constructs and validates a [SearchRequest]. */
    fun searchVideo(
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
        SearchRequest(queryText, queryMetadata, imageQuery, mode, groupName, streamName, searchSources, searchCombineMode,
            startDate, endDate, offset, limit, textEmbScoreMin, imageEmbScoreMin, versionLancedb)
    )
}

/** Collection discovery, mutation, metadata, and upload operations. */
class CollectionsResource(internal val http: VmodalHttp, internal val signedUploads: SignedUploadTransport) {
    /** Lists accessible collection groups, optionally filtered by mode. */
    fun listGroups(mode: String? = null): GroupsResponse {
        val params = if (mode == null) emptyMap() else mapOf("mode" to mode)
        return GroupsResponse(http.request("GET", Routes.full(Routes.Endpoints.groups), params = params))
    }

    /**
     * Compatibility helper for small files. New video code should call videoUploadAsync because
     * this endpoint is multipart/form-data and does not use the scalable signed-R2 flow.
     */
    fun uploadFile(
        part: VmodalFilePart,
        groupName: String = "",
        mode: String = "vid_file",
        streamName: String = "astream",
        description: String = "",
        tag: List<String> = emptyList(),
    ): UploadResponse {
        val form = linkedMapOf<String, Any?>("mode" to mode, "group_name" to groupName, "stream_name" to streamName, "description" to description)
        if (tag.isNotEmpty()) form["tag"] = tag
        return UploadResponse(http.request("POST", Routes.full(Routes.Endpoints.upload), data = form, files = listOf(part)))
    }

    /** Creates a replayable multipart part from [file] and uploads it. */
    fun uploadFile(file: File, groupName: String = "", mode: String = "vid_file", streamName: String = "astream", description: String = "", tag: List<String> = emptyList()) =
        uploadFile(filePart("file", file), groupName, mode, streamName, description, tag)

    /** Always throws [FeatureDisabled] before transport. */
    fun uploadFolder(): Nothing = throw FeatureDisabled("folder upload is disabled on server (cannot scan remote PC/laptop)")

    /** Uploads replayable metadata content and returns a typed result. */
    fun uploadMetadataJsonl(
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
            http.request("POST", Routes.full(Routes.Endpoints.uploadMetadataJsonl), data = form, files = listOf(part))
        } catch (exc: ApiError) {
            if (exc.statusCode != 404) throw exc
            http.request("POST", Routes.Endpoints.uploadMetadataItemParquetInternal, data = form, files = listOf(part))
        }
        return MetadataParquetUploadResponse(body)
    }

    /** Adds existing asset identifiers to a collection. */
    fun addAssets(collectionId: String, assetIds: List<String>, mode: String, groupName: String, streamName: String = "astream"): CollectionAddAssetsResponse {
        val req = CollectionAddAssetsRequest(collectionId, assetIds, mode, groupName, streamName).also { it.validate() }
        val path = Routes.Endpoints.collectionAddAssets.replace("{collection_id}", collectionId)
        return CollectionAddAssetsResponse(http.request("POST", Routes.full(path), json = req.toMap()))
    }

    /** Updates an item's description and/or tags. */
    fun updateDescription(
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
        return CollectionDescriptionUpdateResponse(http.request("POST", Routes.full(Routes.Endpoints.collectionDescriptionUpdate), data = form))
    }

    /** Performs a dry-run or confirmed collection deletion. */
    fun delete(groupName: String, mode: String, scope: String = "all", dryRun: Boolean = false, confirm: Boolean = false): DeleteCollectionResponse {
        val req = DeleteCollectionRequest(groupName, mode, scope, dryRun, confirm).also { it.validate() }
        return DeleteCollectionResponse(http.request("DELETE", Routes.full(Routes.Endpoints.collectionDelete), json = req.toMap()))
    }

    /** Always throws [FeatureDisabled] before transport. */
    fun create(): Nothing = throw FeatureDisabled("no server endpoint; upload creates collection implicitly")
    /** Always throws [FeatureDisabled] before transport. */
    fun edit(): Nothing = throw FeatureDisabled("no server endpoint; upload creates collection implicitly")
    /** Always throws [FeatureDisabled] before transport. */
    fun autoIndexGet(): Nothing = throw FeatureDisabled("collection auto_index is disabled on server")
    /** Always throws [FeatureDisabled] before transport. */
    fun autoIndexSet(): Nothing = throw FeatureDisabled("collection auto_index is disabled on server")
}

/** Index creation, status, listing, and deletion operations. */
class IndexesResource(private val http: VmodalHttp) {
    /** Lists index jobs with optional filters and a bounded result limit. */
    fun jobsList(status: String? = null, mode: String? = null, groupName: String? = null, limit: Int = 200): IndexationJobsListResponse {
        if (limit !in 1..1000) throw ValidationFailed("limit must be between 1 and 1000")
        val params = linkedMapOf<String, Any?>("limit" to limit)
        if (status != null) params["status"] = status
        if (mode != null) params["mode"] = mode
        if (groupName != null) params["group_name"] = groupName
        return IndexationJobsListResponse(http.request("GET", Routes.full(Routes.Endpoints.indexationJobs), params = params))
    }

    /** Validates and creates an index job. */
    fun createIndex(request: IndexationSubmitRequest): IndexationSubmitResponse =
        IndexationSubmitResponse(http.request("POST", Routes.full(Routes.Endpoints.indexationSubmit), json = request.also { it.validate() }.toMap()))

    /** Convenience overload that constructs and validates an index request. */
    fun createIndex(
        mode: String = "", groupName: String = "", indexType: String? = null, modality: String? = null,
        streamName: String? = null, insertMode: String = "append", createIndex: Boolean = true,
        version: String = "new_version", startDate: String? = null, endDate: String? = null,
        embeddingModel: String? = null, reProcess: Boolean = false, dryRun: Boolean = false,
    ) = createIndex(IndexationSubmitRequest(mode, groupName, streamName, indexType, modality, insertMode, createIndex,
        version, startDate, endDate, embeddingModel, reProcess, dryRun))

    /** Returns current status for a required job identifier. */
    fun indexStatus(jobId: String = ""): IndexationStatusResponse {
        val clean = strRequired(jobId, "job_id")
        val path = Routes.Endpoints.indexationStatus.replace("{job_id}", clean)
        return try {
            IndexationStatusResponse(http.request("GET", Routes.full(path)))
        } catch (exc: ApiError) {
            if (exc.statusCode != 404) throw exc
            val row = jobsList(limit = 1000).raw["data"].asObjectList().firstOrNull { it["job_id"]?.toString() == clean } ?: throw exc
            IndexationStatusResponse(row)
        }
    }

    /** Validates and deletes an index version. */
    fun deleteIndex(request: IndexationDeleteRequest): IndexationDeleteResponse =
        IndexationDeleteResponse(http.request("DELETE", Routes.full(Routes.Endpoints.indexationDelete), json = request.also { it.validate() }.toMap()))

    /** Convenience overload that constructs and validates a deletion request. */
    fun deleteIndex(mode: String = "", groupName: String = "", version: String = "", modality: String? = null, dryRun: Boolean = false, confirm: Boolean = false) =
        deleteIndex(IndexationDeleteRequest(mode, groupName, version, modality, dryRun, confirm))

    /** Always throws [FeatureDisabled] before transport. */
    fun embeddingModels(): Nothing = throw FeatureDisabled("embedding models endpoint is disabled on server")
}

/** Administrative statistics and usage operations. */
class AdminResource(private val http: VmodalHttp) {
    /** Returns administrative user statistics. */
    fun userStats() = AdminUserStatsResponse(http.request("GET", Routes.full(Routes.Endpoints.adminUserStats)))
    /** Returns usage counters for [date], or the service default date. */
    fun usage(date: String = ""): UsageUserDetail {
        val params = if (date.isBlank()) emptyMap() else mapOf("date" to date.trim())
        return UsageUserDetail(http.requestUsers("GET", Routes.usersFull(Routes.UsersEndpoints.adminUsage), params = params))
    }
    /** Returns authentication-cache and limiter statistics. */
    fun cacheStats() = CacheStats(http.requestUsers("GET", Routes.usersFull(Routes.UsersEndpoints.adminCacheStats)))
}

/** Low-level signed object-upload helpers. Prefer the video upload extensions. */
class R2Resource(private val http: VmodalHttp) {
    /** Creates a pre-authorized upload location for one file. */
    fun presignUploadFile(mode: String, groupName: String, streamName: String, modality: String, filename: String, expiresIn: Int = 900): PresignedUploadResponse {
        val params = mapOf("mode" to mode, "group_name" to groupName, "stream_name" to streamName, "modality" to modality, "filename" to filename, "expires_in" to expiresIn)
        return PresignedUploadResponse(http.requestUsers("GET", Routes.usersFull(Routes.UsersEndpoints.r2UploadFile), params = params))
    }

    /** Creates pre-authorized upload locations for a video-file batch. */
    fun presignUploadFolderVideo(mode: String, groupName: String, streamName: String, filenames: List<String>, expiresIn: Int = 900): PresignedFolderResponse {
        val body = mapOf("mode" to mode, "group_name" to groupName, "stream_name" to streamName, "filenames" to filenames, "expires_in" to expiresIn)
        return PresignedFolderResponse(http.requestUsers("POST", Routes.usersFull(Routes.UsersEndpoints.r2UploadFolderVideo), json = body))
    }
}

/** Typed image URL and image-content operations. */
class ImagesResource(private val http: VmodalHttp) {
    /** Resolves one image into a temporary signed URL. */
    fun getUrl(mode: String, groupName: String, modality: String, filename: String, streamName: String = "astream", tsUnix13digits: Any? = null, userid: String? = null): ImageUrlResponse {
        val data = linkedMapOf<String, Any?>("mode" to mode, "group_name" to groupName, "modality" to modality, "stream_name" to streamName, "filename" to filename)
        if (tsUnix13digits != null) data["ts_unix_13digits"] = tsUnix13digits.toString()
        if (http.cfg.normalizedMode == "direct" && !userid.isNullOrBlank()) data["userid"] = userid
        val raw = http.request("POST", Routes.full(Routes.Endpoints.imageGetUrl), json = data)
        return ImageUrlResponse(mapAbsoluteImageUrl(raw, http.cfg.normalizedBaseUrl))
    }

    /** Resolves multiple image records into temporary signed URLs. */
    fun getUrlBulk(records: List<Map<String, Any?>>, userid: String? = null): ImageUrlBulkResponse {
        val safeRecords = if (http.cfg.normalizedMode == "direct") records else records.map { it - "userid" - "user_id" }
        val data = linkedMapOf<String, Any?>("records" to safeRecords)
        if (http.cfg.normalizedMode == "direct" && !userid.isNullOrBlank()) data["userid"] = userid
        val raw = http.request("POST", Routes.full(Routes.Endpoints.imageGetUrlBulk), json = data)
        return ImageUrlBulkResponse(mapAbsoluteImageUrls(raw, http.cfg.normalizedBaseUrl))
    }

    /** Downloads bounded image bytes through a signed URL. */
    fun getImageFromUrl(urlPreSigned: String, userid: String? = null): ByteArray {
        val data = linkedMapOf<String, Any?>("url_pre_signed" to urlPreSigned)
        if (http.cfg.normalizedMode == "direct" && !userid.isNullOrBlank()) data["userid"] = userid
        return http.requestBytes("POST", Routes.full(Routes.Endpoints.imageGetImage), json = data)
    }

    /** Downloads a bounded batch of images through signed URLs. */
    fun getImageBulkFromUrls(urls: List<String>, userid: String? = null): ImageGetBulkResponse {
        val data = linkedMapOf<String, Any?>("urls" to urls)
        if (http.cfg.normalizedMode == "direct" && !userid.isNullOrBlank()) data["userid"] = userid
        return ImageGetBulkResponse(http.request("POST", Routes.full(Routes.Endpoints.imageGetImageBulk), json = data))
    }
}

/** Disabled compatibility resource whose methods fail before transport. */
class GDriveResource {
    /** Always throws [FeatureDisabled] before transport. */
    fun privateAuthUrl(): Nothing = throw FeatureDisabled("private google drive auth endpoint is disabled on server")
    /** Always throws [FeatureDisabled] before transport. */
    fun privateDownload(): Nothing = throw FeatureDisabled("private google drive download endpoint is disabled on server")
}

/** Disabled compatibility resource whose methods fail before transport. */
class SqlResource {
    /** Always throws [FeatureDisabled] before transport. */
    fun query(): Nothing = throw FeatureDisabled("sql query endpoint is disabled on server")
}

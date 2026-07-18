package com.vmodal.sdk

/**
 * Base typed response that preserves unrecognized fields.
 *
 * @property raw complete decoded response object
 */
open class JsonBackedResponse(val raw: Map<String, Any?> = emptyMap())

/** Forward-compatible search result item. */
class SearchResultItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Typed collection group summary with version helpers. */
class GroupItem(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Owning user identifier. */
    val userId = raw["user_id"]?.toString().orEmpty()
    /** Collection mode. */
    val mode = raw["mode"]?.toString().orEmpty()
    /** Collection group name. */
    val groupName = raw["group_name"]?.toString().orEmpty()
    /** Legacy video-group value. */
    val videoGroup = raw["video_group"]?.toString().orEmpty()
    /** Modalities present in the group. */
    val modalityTypes = raw["modality_types"].asStringList()
    /** Available database versions. */
    val lancedbVersions = raw["lancedb_versions"].asStringList()
    /** Last-update timestamp when supplied. */
    val lastUpdated = raw["last_updated"]?.toString()

    /** Greatest numeric database version, ignoring malformed labels. */
    val latestLancedbVersion: Int?
        get() = lancedbVersions.mapNotNull { value ->
            Regex("^v(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(value.trim())
                ?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()
}
/** Legacy folder-upload item retained for compatibility. */
class FolderUploadItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Forward-compatible collection asset item. */
class CollectionAsset(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Forward-compatible index job item. */
class IndexationJobItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Forward-compatible administrative statistic item. */
class AdminUserStatItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)

/**
 * Validated multimodal search request and its optional filters.
 *
 * @property queryText natural-language query
 * @property queryMetadata optional metadata filter
 * @property imageQuery optional encoded or referenced image query
 * @property mode collection mode
 * @property groupName collection group name
 * @property streamName stream name
 * @property searchSources modalities to search
 * @property searchCombineMode modality result combination mode
 * @property startDate optional inclusive start date
 * @property endDate optional inclusive end date
 * @property offset result offset
 * @property limit maximum result count
 * @property textEmbScoreMin minimum text similarity score
 * @property imageEmbScoreMin minimum image similarity score
 * @property versionLancedb optional database version
 */
data class SearchRequest(
    val queryText: String = "",
    val queryMetadata: Map<String, Any?>? = null,
    val imageQuery: String? = null,
    val mode: String = "vid_file",
    val groupName: String = "agroup",
    val streamName: String = "astream",
    val searchSources: List<String> = listOf("ocr", "asr", "image"),
    val searchCombineMode: String = "union",
    val startDate: String? = null,
    val endDate: String? = null,
    val offset: Int = 0,
    val limit: Int = 50,
    val textEmbScoreMin: Double = 0.90,
    val imageEmbScoreMin: Double = 1.5,
    val versionLancedb: Int? = null,
) {
    /** Validates that at least one query representation is present. */
    fun validate() {
        if (queryText.isBlank() && imageQuery.isNullOrBlank()) {
            throw ValidationFailed("query_text or image_query is required")
        }
    }

    /** Converts this request to its wire-field map. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "query_text" to queryText,
        "query_metadata" to queryMetadata,
        "image_query" to imageQuery,
        "mode" to mode,
        "group_name" to groupName,
        "stream_name" to streamName,
        "search_sources" to searchSources,
        "search_combine_mode" to searchCombineMode,
        "start_date" to startDate,
        "end_date" to endDate,
        "offset" to offset,
        "limit" to limit,
        "text_emb_score_min" to textEmbScoreMin,
        "image_emb_score_min" to imageEmbScoreMin,
        "version_lancedb" to versionLancedb,
    ).filterValues { it != null }
}

/**
 * Dry-run or confirmed collection deletion request.
 *
 * @property groupName collection group name
 * @property mode collection mode
 * @property scope deletion scope
 * @property dryRun whether to preview without mutation
 * @property confirm explicit deletion confirmation
 */
data class DeleteCollectionRequest(
    val groupName: String,
    val mode: String,
    val scope: String = "all",
    val dryRun: Boolean = false,
    val confirm: Boolean = false,
) {
    /** Validates required collection identifiers. */
    fun validate() {
        strRequired(groupName, "group_name")
        strRequired(mode, "mode")
    }

    /** Converts this request to its wire-field map. */
    fun toMap(): Map<String, Any?> = mapOf(
        "group_name" to groupName,
        "mode" to mode,
        "scope" to scope,
        "dry_run" to dryRun,
        "confirm" to confirm,
    )
}

/**
 * Request to associate existing asset identifiers with a collection.
 *
 * @property collectionId target collection identifier
 * @property assetIds asset identifiers to associate
 * @property mode collection mode
 * @property groupName collection group name
 * @property streamName stream name
 */
data class CollectionAddAssetsRequest(
    val collectionId: String,
    val assetIds: List<String>,
    val mode: String,
    val groupName: String,
    val streamName: String = "astream",
) {
    /** Validates required identifiers and the non-empty asset list. */
    fun validate() {
        strRequired(collectionId, "collection_id")
        if (assetIds.isEmpty()) throw ValidationFailed("asset_ids is required")
        strRequired(mode, "mode")
        strRequired(groupName, "group_name")
    }

    /** Converts this request to its wire-field map. */
    fun toMap(): Map<String, Any?> = mapOf(
        "collection_id" to collectionId,
        "asset_ids" to assetIds,
        "mode" to mode,
        "group_name" to groupName,
        "stream_name" to streamName,
    )
}

/**
 * Index creation request with lifecycle and date-range options.
 *
 * @property mode collection mode
 * @property groupName collection group name
 * @property streamName optional stream name
 * @property indexType optional index implementation
 * @property modality optional modality selector
 * @property insertMode append or replacement behavior
 * @property createIndex whether to build the index after insertion
 * @property version target version label
 * @property startDate optional inclusive start date
 * @property endDate optional inclusive end date
 * @property embeddingModel optional embedding model override
 * @property reProcess whether to process existing data again
 * @property dryRun whether to validate without mutation
 */
data class IndexationSubmitRequest(
    val mode: String,
    val groupName: String,
    val streamName: String? = null,
    val indexType: String? = null,
    val modality: String? = null,
    val insertMode: String = "append",
    val createIndex: Boolean = true,
    val version: String = "new_version",
    val startDate: String? = null,
    val endDate: String? = null,
    val embeddingModel: String? = null,
    val reProcess: Boolean = false,
    val dryRun: Boolean = false,
) {
    /** Validates required collection identifiers. */
    fun validate() {
        strRequired(mode, "mode")
        strRequired(groupName, "group_name")
    }

    /** Converts this request to its wire-field map. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "group_name" to groupName,
        "stream_name" to streamName,
        "index_type" to indexType,
        "modality" to modality,
        "insert_mode" to insertMode,
        "create_index" to createIndex,
        "version" to version,
        "start_date" to startDate,
        "end_date" to endDate,
        "embedding_model" to embeddingModel,
        "re_process" to reProcess,
        "dry_run" to dryRun,
    ).filterValues { it != null }
}

/**
 * Index-version deletion request with confirmation controls.
 *
 * @property mode collection mode
 * @property groupName collection group name
 * @property version version to delete
 * @property modality optional modality selector
 * @property dryRun whether to preview without mutation
 * @property confirm explicit deletion confirmation
 */
data class IndexationDeleteRequest(
    val mode: String,
    val groupName: String,
    val version: String,
    val modality: String? = null,
    val dryRun: Boolean = false,
    val confirm: Boolean = false,
) {
    /** Validates required index identifiers. */
    fun validate() {
        strRequired(mode, "mode")
        strRequired(groupName, "group_name")
        strRequired(version, "version")
    }

    /** Converts this request to its wire-field map. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "group_name" to groupName,
        "modality" to modality,
        "version" to version,
        "dry_run" to dryRun,
        "confirm" to confirm,
    ).filterValues { it != null }
}

/**
 * Bulk image lookup record.
 *
 * @property mode collection mode
 * @property groupName collection group name
 * @property streamName stream name
 * @property filename image file name
 * @property frameId frame identifier
 * @property userid optional legacy user identifier
 */
data class ImageRecord(
    val mode: String = "",
    val groupName: String = "",
    val streamName: String = "astream",
    val filename: String = "",
    val frameId: String = "",
    val userid: String? = null,
) {
    /** Converts this record to its wire-field map. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "group_name" to groupName,
        "stream_name" to streamName,
        "filename" to filename,
        "frame_id" to frameId,
        "userid" to userid,
    ).filterValues { it != null }
}

/**
 * Bulk signed-image-URL lookup record.
 *
 * @property mode collection mode
 * @property groupName collection group name
 * @property modality modality selector
 * @property streamName stream name
 * @property filename image file name
 * @property tsUnix13digits optional frame timestamp
 */
data class ImageUrlRecord(
    val mode: String = "",
    val groupName: String = "",
    val modality: String = "",
    val streamName: String = "astream",
    val filename: String = "",
    val tsUnix13digits: String? = null,
) {
    /** Converts this record to its wire-field map. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "group_name" to groupName,
        "modality" to modality,
        "stream_name" to streamName,
        "filename" to filename,
        "ts_unix_13digits" to tsUnix13digits,
    ).filterValues { it != null }
}

/** Typed availability result. */
class HealthResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Service status label. */
    val status: String = raw["status"]?.toString().orEmpty()
    /** Server timestamp. */
    val timestamp: String? = raw["timestamp"]?.toString()
    /** Service version. */
    val version: String? = raw["version"]?.toString()
    /** Server Python version. */
    val pythonVersion: String? = raw["python_version"]?.toString()
    /** Dependency health details. */
    val dependencies: Any? = raw["dependencies"]
}

/** Typed search result page with counts and elapsed time. */
class SearchResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Ordered result records. */
    val data: List<Any?> = raw["data"] as? List<Any?> ?: emptyList()
    /** Count returned in this page. */
    val cntActual: Int = raw["cnt_actual"].asInt()
    /** Total matching count. */
    val cntTotal: Int = raw["cnt_total"].asInt()
    /** Server execution time in milliseconds. */
    val executionTimeMs: Double = raw["execution_time_ms"].asDouble()
}

/** Collection group page with normalized lookup support. */
class GroupsResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Collection groups in this page. */
    val data: List<GroupItem> = raw["data"].asObjectList().map(::GroupItem)
    /** Total collection group count. */
    val total: Int = raw["total"].asInt()
    /** Server execution time in milliseconds. */
    val executionTimeMs: Double = raw["execution_time_ms"].asDouble()

    /** Finds a normalized group name and optional mode. */
    fun findGroup(groupName: String, mode: String? = null): GroupItem? {
        val name = groupName.trim()
        return data.firstOrNull { item ->
            item.groupName.trim() == name && (mode == null || item.mode == mode)
        }
    }
}

/** Pre-authorized single-upload session details. */
class ExternalUploadSignedUrlResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Owning user identifier. */
    val userId = raw["user_id"]?.toString().orEmpty()
    /** Signed URL lifetime in seconds. */
    val expiresIn = raw["expires_in"].asInt()
    /** Destination object key. */
    val key = raw["key"]?.toString().orEmpty()
    /** Pre-authorized upload URL. */
    val url = raw["url"]?.toString().orEmpty()
    /** Required upload method. */
    val method = raw["method"]?.toString() ?: "PUT"
}

/** Completed multipart part state. */
class MultipartPart(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** One-based part number. */
    val partNumber = raw["part_number"].asInt()
    /** Uploaded part entity tag. */
    val etag = raw["etag"]?.toString().orEmpty()
    /** Uploaded part size. */
    val sizeBytes = raw["size_bytes"].asLong()
}

/** Pre-authorized multipart part details. */
class MultipartSignedPart(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** One-based part number. */
    val partNumber = raw["part_number"].asInt()
    /** Pre-authorized part URL. */
    val url = raw["url"]?.toString().orEmpty()
    /** Required upload method. */
    val method = raw["method"]?.toString() ?: "PUT"
    /** Headers required for this part. */
    val headers = (raw["headers"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
}

/** Newly created multipart session. */
class MultipartCreateResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Idempotency request identifier. */
    val requestId = raw["request_id"]?.toString().orEmpty()
    /** Multipart upload identifier. */
    val uploadId = raw["upload_id"]?.toString().orEmpty()
    /** Destination object key. */
    val key = raw["key"]?.toString().orEmpty()
    /** Total object size. */
    val sizeBytes = raw["size_bytes"].asLong()
    /** Planned bytes per part. */
    val partSizeBytes = raw["part_size_bytes"].asLong()
    /** Planned part count. */
    val partCount = raw["part_count"].asInt()
    /** Multipart session status. */
    val status = raw["status"]?.toString() ?: "created"
}

/** Batch of pre-authorized multipart parts. */
class MultipartSignResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Signed parts in this batch. */
    val parts = raw["parts"].asObjectList().map(::MultipartSignedPart)
    /** Signed URL lifetime in seconds. */
    val expiresIn = raw["expires_in"].asInt()
}

/** Reconciled multipart session status. */
class MultipartStatusResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Multipart session status. */
    val status = raw["status"]?.toString() ?: "uploading"
    /** Parts already accepted by storage. */
    val parts = raw["parts"].asObjectList().map(::MultipartPart)
    /** Completed object entity tag, when available. */
    val etag = raw["etag"]?.toString().orEmpty()
    /** Current or completed object size. */
    val sizeBytes = raw["size_bytes"].asLong()
}

/** Completed multipart object state. */
class MultipartCompleteResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Final multipart session status. */
    val status = raw["status"]?.toString() ?: "completed"
    /** Destination object key. */
    val key = raw["key"]?.toString().orEmpty()
    /** Completed object entity tag. */
    val etag = raw["etag"]?.toString().orEmpty()
    /** Completed object size. */
    val sizeBytes = raw["size_bytes"].asLong()
    /** Whether a prior request already completed the upload. */
    val alreadyCompleted = raw["already_completed"] as? Boolean ?: false
}

/** Legacy multipart endpoint response retained for source compatibility. */
class UploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Complete signed video-upload result including resume and retry metadata. */
class VideoUploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Owning user identifier. */
    val userId = raw["user_id"]?.toString().orEmpty()
    /** Destination object key. */
    val key = raw["key"]?.toString().orEmpty()
    /** Signed upload URL when returned. */
    val url = raw["url"]?.toString().orEmpty()
    /** Upload method. */
    val method = raw["method"]?.toString() ?: "PUT"
    /** Uploaded file name. */
    val fileName = raw["filename"]?.toString().orEmpty()
    /** Uploaded byte size. */
    val sizeBytes = raw["size_bytes"].asLong()
    /** Final transport status. */
    val statusCode = raw["status_code"].asInt()
    /** Whether object bytes were uploaded. */
    val uploaded = raw["uploaded"] as? Boolean ?: false
    /** Single or multipart strategy. */
    val uploadStrategy = raw["upload_strategy"]?.toString() ?: "single"
    /** Multipart upload identifier. */
    val uploadId = raw["upload_id"]?.toString().orEmpty()
    /** Completed object entity tag. */
    val etag = raw["etag"]?.toString().orEmpty()
    /** Multipart bytes per part. */
    val partSizeBytes = raw["part_size_bytes"].asLong()
    /** Total multipart part count. */
    val partCount = raw["part_count"].asInt()
    /** Successfully uploaded part count. */
    val partsUploaded = raw["parts_uploaded"].asInt()
    /** Whether a prior checkpoint was resumed. */
    val resumed = raw["resumed"] as? Boolean ?: false
    /** Aggregate transport attempt count. */
    val attemptCount = raw["attempt_count"].asInt()
    /** Normalized destination path. */
    val destPath = raw["dest_path"]?.toString().orEmpty()
}
/** Ordered results for a bulk video upload. */
class VideoUploadBulkResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Ordered upload results. */
    val data = raw["data"].asObjectList().map(::VideoUploadResponse)
    /** Total upload result count. */
    val total = raw["total"].asInt()
}
/** Legacy disabled folder-upload response retained for compatibility. */
class FolderUploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Metadata upload result with forward-compatible raw data. */
class MetadataParquetUploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Description update result with forward-compatible raw data. */
class CollectionDescriptionUpdateResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Collection deletion result with forward-compatible raw data. */
class DeleteCollectionResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
/** Asset association result with forward-compatible raw data. */
class CollectionAddAssetsResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)

/** Typed page of index jobs. */
class IndexationJobsListResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Index jobs in this page. */
    val data: List<Any?> = raw["data"] as? List<Any?> ?: emptyList()
    /** Total index job count. */
    val total: Int = raw["total"].asInt()
}

/** Index job creation result. */
class IndexationSubmitResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Created job identifier. */
    val jobId: String = raw["job_id"]?.toString().orEmpty()
    /** Initial job status. */
    val status: String = raw["status"]?.toString().orEmpty()
}

/** Current index job state. */
class IndexationStatusResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Index job identifier. */
    val jobId: String = raw["job_id"]?.toString().orEmpty()
    /** Current job status. */
    val status: String = raw["status"]?.toString().orEmpty()
}

/** Index deletion result. */
class IndexationDeleteResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Deletion status. */
    val status: String = raw["status"]?.toString().orEmpty()
}

/** Administrative user-statistics result. */
class AdminUserStatsResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** User statistic records. */
    val data: List<Any?> = raw["data"] as? List<Any?> ?: emptyList()
    /** Total statistic record count. */
    val total: Int = raw["total"].asInt()
}

/** Authenticated credential-owner profile. */
class UserProfile(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Authenticated user identifier. */
    val userId = raw["user_id"]?.toString()
    /** Authenticated email address. */
    val email = raw["email"]?.toString()
    /** Display name. */
    val name = raw["name"]?.toString()
    /** Assigned roles. */
    val roles = raw["roles"] as? List<Any?> ?: emptyList()
    /** Tenant identifier. */
    val tenantId = raw["tenant_id"]?.toString()
    /** Granted permission names. */
    val permissions = (raw["permissions"] as? List<*>)?.map { it.toString() } ?: emptyList()
    /** Profile type. */
    val type = raw["type"]?.toString() ?: "user"
}

/** Usage totals for one user and date. */
class UsageUserDetail(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Usage date. */
    val date = raw["date"]?.toString().orEmpty()
    /** User identifier. */
    val userId = raw["user_id"]?.toString().orEmpty()
    /** Total request count. */
    val total = raw["total"].asInt()
    /** Request count by endpoint category. */
    val endpoints = (raw["endpoints"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.asInt() } ?: emptyMap()
}

/** Administrative cache and limiter counters. */
class CacheStats(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Cached API-key entry count. */
    val apikeyCacheSize = raw["apikey_cache_size"].asInt()
    /** Active rate-limiter bucket count. */
    val rateLimiterBuckets = raw["rate_limiter_buckets"].asInt()
    @Suppress("UNCHECKED_CAST")
    /** Effective cache configuration. */
    val config = raw["config"] as? Map<String, Any?> ?: emptyMap()
}

/** Pre-authorized object upload details. */
class PresignedUploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Owning user identifier. */
    val userId = raw["user_id"]?.toString().orEmpty()
    /** Signed URL lifetime in seconds. */
    val expiresIn = raw["expires_in"].asInt()
    /** Destination object key. */
    val key = raw["key"]?.toString().orEmpty()
    /** Pre-authorized upload URL. */
    val url = raw["url"]?.toString().orEmpty()
    /** Required upload method. */
    val method = raw["method"]?.toString() ?: "PUT"
}

/** One pre-authorized file in a folder-upload response. */
class PresignedFolderItem(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Relative file name. */
    val filename = raw["filename"]?.toString().orEmpty()
    /** Destination object key. */
    val key = raw["key"]?.toString().orEmpty()
    /** Pre-authorized upload URL. */
    val url = raw["url"]?.toString().orEmpty()
    /** Required upload method. */
    val method = raw["method"]?.toString() ?: "PUT"
}

/** Batch of pre-authorized folder-upload files. */
class PresignedFolderResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Owning user identifier. */
    val userId = raw["user_id"]?.toString().orEmpty()
    /** Signed URL lifetime in seconds. */
    val expiresIn = raw["expires_in"].asInt()
    /** Signed files in request order. */
    val files = raw["files"].asObjectList().map(::PresignedFolderItem)
}

/** Signed image URL lookup result. */
class ImageUrlResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Whether an image was resolved. */
    val found = raw["found"] as? Boolean ?: false
    /** Pre-authorized image URL. */
    val urlPreSigned = raw["url_pre_signed"]?.toString().orEmpty()
    /** Resolved storage path. */
    val fullPath = raw["full_path"]?.toString().orEmpty()
    /** URL lifetime in seconds. */
    val expireSec = raw["expire_sec"].asInt()
    /** Service error text when resolution failed. */
    val error = raw["error"]?.toString().orEmpty()
}

/** Bulk signed image URL results. */
class ImageUrlBulkResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** URL result records. */
    val records = raw["records"].asObjectList()
}

/** Bulk image-content results. */
class ImageGetBulkResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Image-content result records. */
    val records = raw["records"].asObjectList()
}

/** Base64 image-content result. */
class ImageResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Whether an image was resolved. */
    val found = raw["found"] as? Boolean ?: false
    /** Base64-encoded image bytes. */
    val imgBase64 = raw["img_base64"]?.toString().orEmpty()
}

/** Image content plus its resolved storage path. */
class FullPathImageResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Whether an image was resolved. */
    val found = raw["found"] as? Boolean ?: false
    /** Base64-encoded image bytes. */
    val imgBase64 = raw["img_base64"]?.toString().orEmpty()
    /** Resolved storage path. */
    val fullpath = raw["fullpath"]?.toString().orEmpty()
}

/** Forward-compatible bulk image response. */
class ImageBulkResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    /** Forward-compatible image records. */
    val records = raw["records"].asObjectList()
}

internal fun Any?.asInt(): Int = when (this) {
    is Byte, is Short, is Int -> (this as Number).toInt()
    is Long -> if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else 0
    is Number -> toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt() ?: 0
    is String -> toIntOrNull() ?: 0
    else -> 0
}

internal fun Any?.asLong(): Long = when (this) {
    is Byte, is Short, is Int, is Long -> (this as Number).toLong()
    is Number -> toDouble().takeIf {
        it.isFinite() && it % 1.0 == 0.0 && kotlin.math.abs(it) <= 9_007_199_254_740_991.0
    }?.toLong() ?: 0
    is String -> toLongOrNull() ?: 0
    else -> 0
}

internal fun Any?.asDouble(): Double = when (this) {
    is Number -> toDouble().takeIf { it.isFinite() } ?: 0.0
    is String -> toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    else -> 0.0
}

internal fun strRequired(value: String, fieldName: String): String {
    val clean = value.trim()
    if (clean.isBlank()) throw ValidationFailed("$fieldName is required")
    return clean
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObjectList(): List<Map<String, Any?>> =
    (this as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

internal fun Any?.asStringList(): List<String> =
    (this as? List<*>)?.map { it.toString() } ?: emptyList()

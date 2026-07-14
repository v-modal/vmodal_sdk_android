package com.vmodal.sdk

open class JsonBackedResponse(val raw: Map<String, Any?> = emptyMap())

class SearchResultItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class GroupItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class FolderUploadItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class CollectionAsset(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class IndexationJobItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class AdminUserStatItem(raw: Map<String, Any?>) : JsonBackedResponse(raw)

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
    fun validate() {
        if (queryText.isBlank() && imageQuery.isNullOrBlank()) {
            throw ValidationFailed("query_text or image_query is required")
        }
    }

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

data class DeleteCollectionRequest(
    val groupName: String,
    val mode: String,
    val scope: String = "all",
    val dryRun: Boolean = false,
    val confirm: Boolean = false,
) {
    fun validate() {
        strRequired(groupName, "group_name")
        strRequired(mode, "mode")
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "group_name" to groupName,
        "mode" to mode,
        "scope" to scope,
        "dry_run" to dryRun,
        "confirm" to confirm,
    )
}

data class CollectionAddAssetsRequest(
    val collectionId: String,
    val assetIds: List<String>,
    val mode: String,
    val groupName: String,
    val streamName: String = "astream",
) {
    fun validate() {
        strRequired(collectionId, "collection_id")
        if (assetIds.isEmpty()) throw ValidationFailed("asset_ids is required")
        strRequired(mode, "mode")
        strRequired(groupName, "group_name")
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "collection_id" to collectionId,
        "asset_ids" to assetIds,
        "mode" to mode,
        "group_name" to groupName,
        "stream_name" to streamName,
    )
}

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
    fun validate() {
        strRequired(mode, "mode")
        strRequired(groupName, "group_name")
    }

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

data class IndexationDeleteRequest(
    val mode: String,
    val groupName: String,
    val version: String,
    val modality: String? = null,
    val dryRun: Boolean = false,
    val confirm: Boolean = false,
) {
    fun validate() {
        strRequired(mode, "mode")
        strRequired(groupName, "group_name")
        strRequired(version, "version")
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "group_name" to groupName,
        "modality" to modality,
        "version" to version,
        "dry_run" to dryRun,
        "confirm" to confirm,
    ).filterValues { it != null }
}

data class ImageRecord(
    val mode: String = "",
    val groupName: String = "",
    val streamName: String = "astream",
    val filename: String = "",
    val frameId: String = "",
    val userid: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "group_name" to groupName,
        "stream_name" to streamName,
        "filename" to filename,
        "frame_id" to frameId,
        "userid" to userid,
    ).filterValues { it != null }
}

data class ImageUrlRecord(
    val mode: String = "",
    val groupName: String = "",
    val modality: String = "",
    val streamName: String = "astream",
    val filename: String = "",
    val tsUnix13digits: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "group_name" to groupName,
        "modality" to modality,
        "stream_name" to streamName,
        "filename" to filename,
        "ts_unix_13digits" to tsUnix13digits,
    ).filterValues { it != null }
}

class HealthResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val status: String = raw["status"]?.toString().orEmpty()
    val timestamp: String? = raw["timestamp"]?.toString()
    val version: String? = raw["version"]?.toString()
    val pythonVersion: String? = raw["python_version"]?.toString()
    val dependencies: Any? = raw["dependencies"]
}

class SearchResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val data: List<Any?> = raw["data"] as? List<Any?> ?: emptyList()
    val cntActual: Int = raw["cnt_actual"].asInt()
    val cntTotal: Int = raw["cnt_total"].asInt()
    val executionTimeMs: Double = raw["execution_time_ms"].asDouble()
}

class GroupsResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val data: List<Any?> = raw["data"] as? List<Any?> ?: emptyList()
    val total: Int = raw["total"].asInt()
    val executionTimeMs: Double = raw["execution_time_ms"].asDouble()
}

class ExternalUploadSignedUrlResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val userId = raw["user_id"]?.toString().orEmpty()
    val expiresIn = raw["expires_in"].asInt()
    val key = raw["key"]?.toString().orEmpty()
    val url = raw["url"]?.toString().orEmpty()
    val method = raw["method"]?.toString() ?: "PUT"
}

class MultipartPart(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val partNumber = raw["part_number"].asInt()
    val etag = raw["etag"]?.toString().orEmpty()
    val sizeBytes = raw["size_bytes"].asLong()
}

class MultipartSignedPart(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val partNumber = raw["part_number"].asInt()
    val url = raw["url"]?.toString().orEmpty()
    val method = raw["method"]?.toString() ?: "PUT"
    val headers = (raw["headers"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
}

class MultipartCreateResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val requestId = raw["request_id"]?.toString().orEmpty()
    val uploadId = raw["upload_id"]?.toString().orEmpty()
    val key = raw["key"]?.toString().orEmpty()
    val sizeBytes = raw["size_bytes"].asLong()
    val partSizeBytes = raw["part_size_bytes"].asLong()
    val partCount = raw["part_count"].asInt()
    val status = raw["status"]?.toString() ?: "created"
}

class MultipartSignResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val parts = raw["parts"].asObjectList().map(::MultipartSignedPart)
    val expiresIn = raw["expires_in"].asInt()
}

class MultipartStatusResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val status = raw["status"]?.toString() ?: "uploading"
    val parts = raw["parts"].asObjectList().map(::MultipartPart)
    val etag = raw["etag"]?.toString().orEmpty()
    val sizeBytes = raw["size_bytes"].asLong()
}

class MultipartCompleteResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val status = raw["status"]?.toString() ?: "completed"
    val key = raw["key"]?.toString().orEmpty()
    val etag = raw["etag"]?.toString().orEmpty()
    val sizeBytes = raw["size_bytes"].asLong()
    val alreadyCompleted = raw["already_completed"] as? Boolean ?: false
}

/** Legacy multipart endpoint response retained for source compatibility. */
class UploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class VideoUploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val userId = raw["user_id"]?.toString().orEmpty()
    val key = raw["key"]?.toString().orEmpty()
    val url = raw["url"]?.toString().orEmpty()
    val method = raw["method"]?.toString() ?: "PUT"
    val fileName = raw["filename"]?.toString().orEmpty()
    val sizeBytes = raw["size_bytes"].asLong()
    val statusCode = raw["status_code"].asInt()
    val uploaded = raw["uploaded"] as? Boolean ?: false
    val uploadStrategy = raw["upload_strategy"]?.toString() ?: "single"
    val uploadId = raw["upload_id"]?.toString().orEmpty()
    val etag = raw["etag"]?.toString().orEmpty()
    val partSizeBytes = raw["part_size_bytes"].asLong()
    val partCount = raw["part_count"].asInt()
    val partsUploaded = raw["parts_uploaded"].asInt()
    val resumed = raw["resumed"] as? Boolean ?: false
    val attemptCount = raw["attempt_count"].asInt()
    val destPath = raw["dest_path"]?.toString().orEmpty()
}
class VideoUploadBulkResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val data = raw["data"].asObjectList().map(::VideoUploadResponse)
    val total = raw["total"].asInt()
}
class FolderUploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class MetadataParquetUploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class CollectionDescriptionUpdateResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class DeleteCollectionResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)
class CollectionAddAssetsResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw)

class IndexationJobsListResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val data: List<Any?> = raw["data"] as? List<Any?> ?: emptyList()
    val total: Int = raw["total"].asInt()
}

class IndexationSubmitResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val jobId: String = raw["job_id"]?.toString().orEmpty()
    val status: String = raw["status"]?.toString().orEmpty()
}

class IndexationStatusResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val jobId: String = raw["job_id"]?.toString().orEmpty()
    val status: String = raw["status"]?.toString().orEmpty()
}

class IndexationDeleteResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val status: String = raw["status"]?.toString().orEmpty()
}

class AdminUserStatsResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val data: List<Any?> = raw["data"] as? List<Any?> ?: emptyList()
    val total: Int = raw["total"].asInt()
}

class UserProfile(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val userId = raw["user_id"]?.toString()
    val email = raw["email"]?.toString()
    val name = raw["name"]?.toString()
    val roles = raw["roles"] as? List<Any?> ?: emptyList()
    val tenantId = raw["tenant_id"]?.toString()
    val permissions = (raw["permissions"] as? List<*>)?.map { it.toString() } ?: emptyList()
    val type = raw["type"]?.toString() ?: "user"
}

class UsageUserDetail(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val date = raw["date"]?.toString().orEmpty()
    val userId = raw["user_id"]?.toString().orEmpty()
    val total = raw["total"].asInt()
    val endpoints = (raw["endpoints"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.asInt() } ?: emptyMap()
}

class CacheStats(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val apikeyCacheSize = raw["apikey_cache_size"].asInt()
    val rateLimiterBuckets = raw["rate_limiter_buckets"].asInt()
    @Suppress("UNCHECKED_CAST")
    val config = raw["config"] as? Map<String, Any?> ?: emptyMap()
}

class R2CredentialsResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val userId = raw["user_id"]?.toString().orEmpty()
    val accessKeyId = raw["access_key_id"]?.toString().orEmpty()
    val secretKey = raw["secret_key"]?.toString().orEmpty()
    val sessionToken = raw["session_token"]?.toString().orEmpty()
    val expiryDate = raw["expiry_date"]?.toString().orEmpty()
    val endpointUrl = raw["vmodal_r2_endpoint_url"]?.toString().orEmpty()
    val basePrefix = raw["vmodal_base_prefix"]?.toString().orEmpty()
    val bucket = raw["vmodal_bucket"]?.toString().orEmpty()
    val dataUploadBucket = raw["CLI_USER_R2_BUCKET_DATA_UPLOAD"]?.toString().orEmpty()
    val dataUploadPrefix = raw["CLI_USER_PREFIX_DATA_UPLOAD"]?.toString().orEmpty()
}

class PresignedUploadResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val userId = raw["user_id"]?.toString().orEmpty()
    val expiresIn = raw["expires_in"].asInt()
    val key = raw["key"]?.toString().orEmpty()
    val url = raw["url"]?.toString().orEmpty()
    val method = raw["method"]?.toString() ?: "PUT"
}

class PresignedFolderItem(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val filename = raw["filename"]?.toString().orEmpty()
    val key = raw["key"]?.toString().orEmpty()
    val url = raw["url"]?.toString().orEmpty()
    val method = raw["method"]?.toString() ?: "PUT"
}

class PresignedFolderResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val userId = raw["user_id"]?.toString().orEmpty()
    val expiresIn = raw["expires_in"].asInt()
    val files = raw["files"].asObjectList().map(::PresignedFolderItem)
}

class ImageUrlResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val found = raw["found"] as? Boolean ?: false
    val urlPreSigned = raw["url_pre_signed"]?.toString().orEmpty()
    val fullPath = raw["full_path"]?.toString().orEmpty()
    val expireSec = raw["expire_sec"].asInt()
    val error = raw["error"]?.toString().orEmpty()
}

class ImageUrlBulkResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val records = raw["records"].asObjectList()
}

class ImageGetBulkResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val records = raw["records"].asObjectList()
}

class ImageResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val found = raw["found"] as? Boolean ?: false
    val imgBase64 = raw["img_base64"]?.toString().orEmpty()
}

class FullPathImageResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val found = raw["found"] as? Boolean ?: false
    val imgBase64 = raw["img_base64"]?.toString().orEmpty()
    val fullpath = raw["fullpath"]?.toString().orEmpty()
}

class ImageBulkResponse(raw: Map<String, Any?>) : JsonBackedResponse(raw) {
    val records = raw["records"].asObjectList()
}

internal fun Any?.asInt(): Int = when (this) {
    is Number -> toInt()
    is String -> toIntOrNull() ?: 0
    else -> 0
}

internal fun Any?.asLong(): Long = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull() ?: 0
    else -> 0
}

internal fun Any?.asDouble(): Double = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull() ?: 0.0
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

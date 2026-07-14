package com.vmodal.sdk

object Routes {
    const val PREFIX = "/api/external/v1"
    const val USERS_API_PREFIX = "/api/v1"

    const val externalUploadGetSignedUrl = "/collections/external_upload_get_signed_url"
    const val externalUploadDone = "/collection/upload/done"
    const val externalUploadMultipartCreate = "/collections/external_upload_multipart/create"
    const val externalUploadMultipartSignParts = "/collections/external_upload_multipart/sign_parts"
    const val externalUploadMultipartStatus = "/collections/external_upload_multipart/status"
    const val externalUploadMultipartComplete = "/collections/external_upload_multipart/complete"
    const val externalUploadMultipartAbort = "/collections/external_upload_multipart/abort"

    object Endpoints {
        const val health = "/health"
        const val searchClient = "/search"
        const val groups = "/collection/groups"
        const val indexationJobs = "/indexation/jobs"
        const val indexationSubmit = "/indexation/job/create"
        const val indexationStatus = "/indexation/job/{job_id}"
        const val indexationDelete = "/indexation/index/delete"
        const val upload = "/collection/upload"
        const val uploadFolder = "/upload/folder"
        const val uploadMetadataJsonl = "/collection/upload/metadata"
        const val collectionDescriptionUpdate = "/collection/description/update"
        const val uploadMetadataItemParquetInternal = "/api/internal/v1/collection/upload/metadata"
        const val collectionDelete = "/collection/delete"
        const val collectionAddAssets = "/collection/{collection_id}/assets/create"
        const val adminUserStats = "/admin/user-stats"
        const val imageGetUrl = "/image/get_url"
        const val imageGetUrlBulk = "/image/get_url_bulk"
        const val imageGetImage = "/image/get_image"
        const val imageGetImageBulk = "/image/get_image_bulk"
    }

    object UsersEndpoints {
        const val authMe = "/auth/me"
        const val adminUsage = "/admin/usage"
        const val adminCacheStats = "/admin/cache/stats"
        const val r2Credentials = "/get_r2_credentials/"
        const val r2UploadFile = "/upload_file/"
        const val r2UploadFolderVideo = "/upload_folder_video/"
    }

    val activeEndpoints: Map<String, Pair<String, String>> = linkedMapOf(
        "auth.health" to ("GET" to Endpoints.health),
        "auth.auth_check" to ("GET" to Endpoints.health),
        "searches.search_video" to ("POST" to Endpoints.searchClient),
        "collections.list_groups" to ("GET" to Endpoints.groups),
        "collections.upload_metadata_jsonl" to ("POST" to Endpoints.uploadMetadataJsonl),
        "collections.update_description" to ("POST" to Endpoints.collectionDescriptionUpdate),
        "collections.delete" to ("DELETE" to Endpoints.collectionDelete),
        "collections.add_assets" to ("POST" to Endpoints.collectionAddAssets),
        "indexes.jobs_list" to ("GET" to Endpoints.indexationJobs),
        "indexes.create_index" to ("POST" to Endpoints.indexationSubmit),
        "indexes.index_status" to ("GET" to Endpoints.indexationStatus),
        "indexes.delete_index" to ("DELETE" to Endpoints.indexationDelete),
        "admin.user_stats" to ("GET" to Endpoints.adminUserStats),
        "images.get_url" to ("POST" to Endpoints.imageGetUrl),
        "images.get_url_bulk" to ("POST" to Endpoints.imageGetUrlBulk),
        "images.get_image_from_url" to ("POST" to Endpoints.imageGetImage),
        "images.get_image_bulk_from_urls" to ("POST" to Endpoints.imageGetImageBulk),
    )

    val disabledEndpoints: Map<String, Pair<String, String>> = linkedMapOf(
        "collections.upload_folder" to ("POST" to Endpoints.uploadFolder),
        "indexes.embedding_models" to ("GET" to "/indexes/embedding_models"),
        "collections.auto_index_get" to ("GET" to "/collection/auto_index"),
        "collections.auto_index_set" to ("POST" to "/collection/auto_index"),
        "collections.create" to ("NONE" to ""),
        "collections.edit" to ("NONE" to ""),
        "gdrive.private_auth_url" to ("POST" to "/gdrive/private/auth-url"),
        "gdrive.private_download" to ("POST" to "/gdrive/private/folder/download"),
        "sql.query" to ("POST" to "/sql/query"),
    )

    fun full(path: String): String = addPrefix(path, PREFIX)
    fun usersFull(path: String): String = addPrefix(path, USERS_API_PREFIX)

    private fun addPrefix(path: String, prefix: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val clean = if (path.startsWith("/")) path else "/$path"
        return prefix + clean
    }
}

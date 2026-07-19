package com.vmodal.sdk

object Routes {
    @JvmField val PREFIX = RoutesGenerated.external_prefix
    @JvmField val USERS_API_PREFIX = RoutesGenerated.users_api_prefix

    @JvmField val externalUploadGetSignedUrl = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_VIDEO_UPLOAD_PRESIGN)
    @JvmField val externalUploadDone = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_VIDEO_UPLOAD_DONE)
    // Production does not currently expose the five multipart routes below.
    // Keep them for sdk_python contract parity and offline regression coverage, but
    // do not enable forced multipart live coverage until the backend supports them.
    @JvmField val externalUploadMultipartCreate = RoutesGenerated.path(RoutesGenerated.ID_MULTIPART_CREATE)
    @JvmField val externalUploadMultipartSignParts = RoutesGenerated.path(RoutesGenerated.ID_MULTIPART_SIGN_PARTS)
    @JvmField val externalUploadMultipartStatus = RoutesGenerated.path(RoutesGenerated.ID_MULTIPART_STATUS)
    @JvmField val externalUploadMultipartComplete = RoutesGenerated.path(RoutesGenerated.ID_MULTIPART_COMPLETE)
    @JvmField val externalUploadMultipartAbort = RoutesGenerated.path(RoutesGenerated.ID_MULTIPART_ABORT)

    object Endpoints {
        @JvmField val health = RoutesGenerated.path(RoutesGenerated.ID_AUTH_HEALTH)
        @JvmField val searchClient = RoutesGenerated.path(RoutesGenerated.ID_SEARCHES_SEARCH_VIDEO)
        @JvmField val groups = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_LIST_GROUPS)
        @JvmField val indexationJobs = RoutesGenerated.path(RoutesGenerated.ID_INDEXES_JOBS_LIST)
        @JvmField val indexationSubmit = RoutesGenerated.path(RoutesGenerated.ID_INDEXES_CREATE_INDEX)
        @JvmField val indexationStatus = RoutesGenerated.path(RoutesGenerated.ID_INDEXES_INDEX_STATUS)
        @JvmField val indexationDelete = RoutesGenerated.path(RoutesGenerated.ID_INDEXES_DELETE_INDEX)
        @JvmField val upload = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_UPLOAD_FILE)
        @JvmField val uploadFolder = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_UPLOAD_FOLDER)
        // Deprecated route retained only for route-table parity. Do not expose it in the SDK.
        @Deprecated("Google Drive upload is deprecated; use signed videoUpload instead")
        @JvmField val uploadGoogleDriveFolder = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_UPLOAD_GOOGLE_DRIVE_FOLDER)
        @JvmField val uploadMetadataJsonl = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_UPLOAD_METADATA_JSONL)
        @JvmField val collectionDescriptionUpdate = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_UPDATE_DESCRIPTION)
        @JvmField val uploadMetadataItemParquetInternal = RoutesGenerated.path(RoutesGenerated.ID_METADATA_INTERNAL_FALLBACK)
        @JvmField val collectionDelete = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_DELETE)
        @JvmField val collectionAddAssets = RoutesGenerated.path(RoutesGenerated.ID_COLLECTIONS_ADD_ASSETS)
        @JvmField val adminUserStats = RoutesGenerated.path(RoutesGenerated.ID_ADMIN_USER_STATS)
        @JvmField val imageGetUrl = RoutesGenerated.path(RoutesGenerated.ID_IMAGES_GET_URL)
        @JvmField val imageGetUrlBulk = RoutesGenerated.path(RoutesGenerated.ID_IMAGES_GET_URL_BULK)
        @JvmField val imageGetImage = RoutesGenerated.path(RoutesGenerated.ID_IMAGES_GET_IMAGE_FROM_URL)
        @JvmField val imageGetImageBulk = RoutesGenerated.path(RoutesGenerated.ID_IMAGES_GET_IMAGE_BULK_FROM_URLS)
    }

    object UsersEndpoints {
        @JvmField val authMe = RoutesGenerated.path(RoutesGenerated.ID_AUTH_ME)
        @JvmField val adminUsage = RoutesGenerated.path(RoutesGenerated.ID_ADMIN_USAGE)
        @JvmField val adminCacheStats = RoutesGenerated.path(RoutesGenerated.ID_ADMIN_CACHE_STATS)
        @JvmField val r2UploadFile = RoutesGenerated.path(RoutesGenerated.ID_R2_PRESIGN_UPLOAD_FILE)
        @JvmField val r2UploadFolderVideo = RoutesGenerated.path(RoutesGenerated.ID_R2_PRESIGN_UPLOAD_FOLDER_VIDEO)
    }

    val activeEndpoints: Map<String, Pair<String, String>> = linkedMapOf(
        "auth.health" to RoutesGenerated.methodPath(RoutesGenerated.ID_AUTH_HEALTH),
        "auth.auth_check" to RoutesGenerated.methodPath(RoutesGenerated.ID_AUTH_AUTH_CHECK),
        "searches.search_video" to RoutesGenerated.methodPath(RoutesGenerated.ID_SEARCHES_SEARCH_VIDEO),
        "collections.list_groups" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_LIST_GROUPS),
        "collections.upload_metadata_jsonl" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_UPLOAD_METADATA_JSONL),
        "collections.update_description" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_UPDATE_DESCRIPTION),
        "collections.delete" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_DELETE),
        "collections.add_assets" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_ADD_ASSETS),
        "indexes.jobs_list" to RoutesGenerated.methodPath(RoutesGenerated.ID_INDEXES_JOBS_LIST),
        "indexes.create_index" to RoutesGenerated.methodPath(RoutesGenerated.ID_INDEXES_CREATE_INDEX),
        "indexes.index_status" to RoutesGenerated.methodPath(RoutesGenerated.ID_INDEXES_INDEX_STATUS),
        "indexes.delete_index" to RoutesGenerated.methodPath(RoutesGenerated.ID_INDEXES_DELETE_INDEX),
        "admin.user_stats" to RoutesGenerated.methodPath(RoutesGenerated.ID_ADMIN_USER_STATS),
        "images.get_url" to RoutesGenerated.methodPath(RoutesGenerated.ID_IMAGES_GET_URL),
        "images.get_url_bulk" to RoutesGenerated.methodPath(RoutesGenerated.ID_IMAGES_GET_URL_BULK),
        "images.get_image_from_url" to RoutesGenerated.methodPath(RoutesGenerated.ID_IMAGES_GET_IMAGE_FROM_URL),
        "images.get_image_bulk_from_urls" to RoutesGenerated.methodPath(RoutesGenerated.ID_IMAGES_GET_IMAGE_BULK_FROM_URLS),
    )

    val disabledEndpoints: Map<String, Pair<String, String>> = linkedMapOf(
        "collections.upload_folder" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_UPLOAD_FOLDER),
        "indexes.embedding_models" to RoutesGenerated.methodPath(RoutesGenerated.ID_INDEXES_EMBEDDING_MODELS),
        "collections.auto_index_get" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_AUTO_INDEX_GET),
        "collections.auto_index_set" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_AUTO_INDEX_SET),
        "collections.create" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_CREATE),
        "collections.edit" to RoutesGenerated.methodPath(RoutesGenerated.ID_COLLECTIONS_EDIT),
        "gdrive.private_auth_url" to RoutesGenerated.methodPath(RoutesGenerated.ID_GDRIVE_PRIVATE_AUTH_URL),
        "gdrive.private_download" to RoutesGenerated.methodPath(RoutesGenerated.ID_GDRIVE_PRIVATE_DOWNLOAD),
        "sql.query" to RoutesGenerated.methodPath(RoutesGenerated.ID_SQL_QUERY),
    )

    fun full(path: String): String = addPrefix(path, PREFIX)
    fun usersFull(path: String): String = addPrefix(path, USERS_API_PREFIX)

    private fun addPrefix(path: String, prefix: String): String {
        if (strIsAbsoluteHttpUrl(path)) return path
        val clean = if (path.startsWith("/")) path else "/$path"
        return prefix + clean
    }
}

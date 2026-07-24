package com.vmodal.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

private const val CONTENT_SEPARATOR = "__"
private const val PROJECT_ID_MAX = 20
private const val COLLECTION_NAME_MAX = 60
private const val STREAM_NAME_MAX = 80
private const val BACKEND_COLLECTION_MAX = 80
private val CONTENT_NAME_PATTERN = Regex("^[A-Za-z0-9_]+$")

internal data class ContentScope private constructor(
    val projectId: String,
    val collectionName: String,
    val streamName: String,
) {
    val backendCollectionName = "$projectId$CONTENT_SEPARATOR$collectionName"

    companion object {
        fun project(projectId: String): String =
            normalize(projectId, "projectId", PROJECT_ID_MAX, reservedSeparator = true)

        fun create(projectId: String, collectionName: String, streamName: String): ContentScope {
            val project = project(projectId)
            val collection = normalize(
                collectionName,
                "collectionName",
                COLLECTION_NAME_MAX,
                reservedSeparator = true,
            )
            val stream = normalize(streamName, "streamName", STREAM_NAME_MAX)
            if (project.length + CONTENT_SEPARATOR.length + collection.length > BACKEND_COLLECTION_MAX) {
                throw ValidationFailed("projectId and collectionName must encode to at most 80 characters")
            }
            return ContentScope(project, collection, stream)
        }

        fun decodeCollection(projectId: String, backendName: String): String? {
            val prefix = "$projectId$CONTENT_SEPARATOR"
            if (!backendName.startsWith(prefix)) return null
            val value = backendName.removePrefix(prefix)
            return try {
                normalize(value, "collectionName", COLLECTION_NAME_MAX, reservedSeparator = true)
            } catch (_: ValidationFailed) {
                throw MalformedResponse("collection listing returned an invalid collectionName")
            }
        }

        private fun normalize(
            value: String,
            field: String,
            maxLength: Int,
            reservedSeparator: Boolean = false,
        ): String {
            val clean = value.trim()
            if (clean.isEmpty()) throw ValidationFailed("$field is required")
            if (clean.length > maxLength) throw ValidationFailed("$field must be at most $maxLength characters")
            if (!CONTENT_NAME_PATTERN.matches(clean)) {
                throw ValidationFailed("$field must contain only letters, digits, and underscore")
            }
            if (reservedSeparator && CONTENT_SEPARATOR in clean) {
                throw ValidationFailed("$field must not contain the reserved separator \"$CONTENT_SEPARATOR\"")
            }
            return clean
        }
    }
}

/**
 * Preferred entry point for project, collection, and stream scoped Android integrations.
 */
object VModal {
    /**
     * Creates an immutable project client without performing network I/O.
     *
     * Authentication remains independent from content organization. When [apiKeyProvider] is
     * supplied, it provides the current credential at request time and supports key rotation.
     */
    fun configure(
        projectId: String,
        apiKey: String,
        baseUrl: String = PUBLIC_GATEWAY_URL,
        timeoutMillis: Int = 30_000,
        maxRetries: Int = 1,
        apiKeyProvider: ApiKeyProvider? = null,
    ): VModalProject {
        val project = ContentScope.project(projectId)
        val cfg = SdkConfig(
            baseUrl = baseUrl,
            token = apiKey,
            timeoutMillis = timeoutMillis,
            maxRetries = maxRetries,
            apiKeyProvider = apiKeyProvider,
        )
        return VModalProject(project, Client(cfg))
    }
}

/**
 * Immutable client for one developer-owned project.
 *
 * @property projectId normalized public project identifier
 */
class VModalProject internal constructor(
    projectId: String,
    client: Client,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val projectId = ContentScope.project(projectId)
    private val api = client.coroutines(dispatcher)

    /** Creates an immutable collection and stream scope without performing network I/O. */
    fun scope(collectionName: String, streamName: String): VModalScope =
        VModalScope(ContentScope.create(projectId, collectionName, streamName), api)

    /**
     * Lists logical collection names owned by this project.
     *
     * Backend order is preserved, duplicates keep their first occurrence, and encoded names are
     * never returned.
     */
    suspend fun listCollections(mode: String? = null): List<String> {
        val found = linkedSetOf<String>()
        api.collections.listGroups(mode).data.forEach { item ->
            ContentScope.decodeCollection(projectId, item.groupName)?.let(found::add)
        }
        return found.toList()
    }
}

/**
 * Immutable operation scope for one project, collection, and stream.
 *
 * Organization values cannot be overridden by individual operations.
 *
 * @property projectId normalized public project identifier
 * @property collectionName normalized public collection name
 * @property streamName normalized public stream name
 */
class VModalScope internal constructor(
    private val scope: ContentScope,
    private val api: CoroutineClient,
) {
    val projectId = scope.projectId
    val collectionName = scope.collectionName
    val streamName = scope.streamName

    /** Uploads and indexes one asset using this scope. */
    suspend fun upload(
        source: UploadSource,
        options: ScopedUploadOptions = ScopedUploadOptions(),
    ): VideoUploadResponse = api.collections.videoUpload(
        source = source,
        collectionName = scope.backendCollectionName,
        subCollectionName = scope.streamName,
        mode = options.mode,
        modality = options.modality,
        ttl = options.ttl,
        options = options.uploadOptions,
    )

    /** Returns a cold upload progress flow using this scope. */
    fun uploadEvents(
        source: UploadSource,
        options: ScopedUploadOptions = ScopedUploadOptions(),
    ): Flow<VideoUploadEvent> = api.collections.videoUploadEvents(
        source = source,
        collectionName = scope.backendCollectionName,
        subCollectionName = scope.streamName,
        mode = options.mode,
        modality = options.modality,
        ttl = options.ttl,
        options = options.uploadOptions,
    )

    /** Uploads collection metadata using this scope. */
    suspend fun uploadMetadata(
        part: VmodalFilePart,
        options: ScopedMetadataOptions = ScopedMetadataOptions(),
    ): MetadataParquetUploadResponse = api.collections.uploadMetadataJsonl(
        part = part,
        mode = options.mode,
        groupName = scope.backendCollectionName,
        streamName = scope.streamName,
        writeMode = options.writeMode,
        allowOverlap = options.allowOverlap,
    )

    /** Searches only this collection and stream. */
    suspend fun search(
        query: String,
        options: ScopedSearchOptions = ScopedSearchOptions(),
    ): SearchResponse = api.searches.searchVideo(
        SearchRequest(
            queryText = query,
            queryMetadata = options.queryMetadata,
            imageQuery = options.imageQuery,
            mode = options.mode,
            groupName = scope.backendCollectionName,
            streamName = scope.streamName,
            searchSources = options.searchSources,
            searchCombineMode = options.searchCombineMode,
            startDate = options.startDate,
            endDate = options.endDate,
            offset = options.offset,
            limit = options.limit,
            textEmbScoreMin = options.textEmbScoreMin,
            imageEmbScoreMin = options.imageEmbScoreMin,
            versionLancedb = options.versionLancedb,
        )
    )

    /** Associates existing asset identifiers with this collection and stream. */
    suspend fun addAssets(
        collectionId: String,
        assetIds: List<String>,
        options: ScopedAddAssetsOptions = ScopedAddAssetsOptions(),
    ): CollectionAddAssetsResponse = api.collections.addAssets(
        collectionId = collectionId,
        assetIds = assetIds,
        mode = options.mode,
        groupName = scope.backendCollectionName,
        streamName = scope.streamName,
    )

    /** Updates one asset in this collection and stream. */
    suspend fun updateAsset(
        filename: String,
        changes: ScopedAssetChanges,
    ): CollectionDescriptionUpdateResponse = api.collections.updateDescription(
        groupName = scope.backendCollectionName,
        mode = changes.mode,
        streamName = scope.streamName,
        filenameSanitized = filename,
        description = changes.description,
        tag = changes.tags,
    )

    /** Creates an index job for this collection and stream. */
    suspend fun createIndex(
        options: ScopedCreateIndexOptions = ScopedCreateIndexOptions(),
    ): IndexationSubmitResponse = api.indexes.createIndex(
        IndexationSubmitRequest(
            mode = options.mode,
            groupName = scope.backendCollectionName,
            streamName = scope.streamName,
            indexType = options.indexType,
            modality = options.modality,
            insertMode = options.insertMode,
            createIndex = options.createIndex,
            version = options.version,
            startDate = options.startDate,
            endDate = options.endDate,
            embeddingModel = options.embeddingModel,
            reProcess = options.reProcess,
            dryRun = options.dryRun,
        )
    )

    /** Lists index jobs filtered to this collection. */
    suspend fun listIndexJobs(
        options: ScopedIndexJobsOptions = ScopedIndexJobsOptions(),
    ): IndexationJobsListResponse = api.indexes.jobsList(
        status = options.status,
        mode = options.mode,
        groupName = scope.backendCollectionName,
        limit = options.limit,
    )

    /** Reads one index job without accepting alternate organization values. */
    suspend fun indexStatus(jobId: String): IndexationStatusResponse =
        api.indexes.indexStatus(jobId)

    /** Deletes one index version from this collection. */
    suspend fun deleteIndex(
        version: String,
        options: ScopedDeleteIndexOptions = ScopedDeleteIndexOptions(),
    ): IndexationDeleteResponse = api.indexes.deleteIndex(
        IndexationDeleteRequest(
            mode = options.mode,
            groupName = scope.backendCollectionName,
            version = version,
            modality = options.modality,
            dryRun = options.dryRun,
            confirm = options.confirm,
        )
    )

    /**
     * Deletes the complete logical collection, including all of its streams.
     *
     * Existing [ScopedDeleteCollectionOptions.dryRun] and
     * [ScopedDeleteCollectionOptions.confirm] safeguards are passed through unchanged.
     */
    suspend fun deleteCollection(
        options: ScopedDeleteCollectionOptions = ScopedDeleteCollectionOptions(),
    ): DeleteCollectionResponse = api.collections.delete(
        groupName = scope.backendCollectionName,
        mode = options.mode,
        scope = options.scope,
        dryRun = options.dryRun,
        confirm = options.confirm,
    )
}

/**
 * Scoped signed-upload behavior without organization fields.
 *
 * @property mode backend media mode
 * @property modality backend media modality
 * @property ttl signed URL lifetime in seconds
 * @property uploadOptions existing upload engine policy
 */
data class ScopedUploadOptions(
    val mode: String = "vid_file",
    val modality: String = "vid_raw",
    val ttl: Int = 12_600,
    val uploadOptions: VideoUploadOptions = VideoUploadOptions(),
)

/**
 * Scoped metadata upload behavior without organization fields.
 *
 * @property mode backend media mode
 * @property writeMode append or replacement behavior
 * @property allowOverlap whether overlapping metadata rows are accepted
 */
data class ScopedMetadataOptions(
    val mode: String = "img_file",
    val writeMode: String = "append",
    val allowOverlap: Boolean = false,
)

/**
 * Scoped search behavior without organization fields.
 *
 * @property queryMetadata optional metadata filter
 * @property imageQuery optional encoded or referenced image query
 * @property mode backend media mode
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
data class ScopedSearchOptions(
    val queryMetadata: Map<String, Any?>? = null,
    val imageQuery: String? = null,
    val mode: String = "vid_file",
    val searchSources: List<String> = listOf("ocr", "asr", "image"),
    val searchCombineMode: String = "union",
    val startDate: String? = null,
    val endDate: String? = null,
    val offset: Int = 0,
    val limit: Int = 50,
    val textEmbScoreMin: Double = 0.90,
    val imageEmbScoreMin: Double = 1.5,
    val versionLancedb: Int? = null,
)

/** Scoped add-assets behavior without organization fields. */
data class ScopedAddAssetsOptions(
    /** Backend media mode. */
    val mode: String = "vid_file",
)

/**
 * Asset changes for the selected scope.
 *
 * @property mode backend media mode
 * @property description replacement description when supplied
 * @property tags replacement tags when supplied
 */
data class ScopedAssetChanges(
    val mode: String = "vid_file",
    val description: String? = null,
    val tags: List<String>? = null,
)

/**
 * Scoped index creation behavior without organization fields.
 *
 * @property mode backend media mode
 * @property indexType optional index implementation
 * @property modality optional media modality
 * @property insertMode append or replacement behavior
 * @property createIndex whether to build the index after insertion
 * @property version target version label
 * @property startDate optional inclusive start date
 * @property endDate optional inclusive end date
 * @property embeddingModel optional embedding model override
 * @property reProcess whether to process existing data again
 * @property dryRun whether to validate without mutation
 */
data class ScopedCreateIndexOptions(
    val mode: String = "vid_file",
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
)

/**
 * Scoped index job filters without organization fields.
 *
 * @property status optional job status
 * @property mode optional backend media mode
 * @property limit maximum job count
 */
data class ScopedIndexJobsOptions(
    val status: String? = null,
    val mode: String? = null,
    val limit: Int = 200,
)

/**
 * Scoped index deletion behavior without organization fields.
 *
 * @property mode backend media mode
 * @property modality optional media modality
 * @property dryRun whether to preview without mutation
 * @property confirm explicit deletion confirmation
 */
data class ScopedDeleteIndexOptions(
    val mode: String = "vid_file",
    val modality: String? = null,
    val dryRun: Boolean = false,
    val confirm: Boolean = false,
)

/**
 * Collection-wide deletion behavior without organization fields.
 *
 * @property mode backend media mode
 * @property scope backend deletion scope
 * @property dryRun whether to preview without mutation
 * @property confirm explicit deletion confirmation
 */
data class ScopedDeleteCollectionOptions(
    val mode: String = "vid_file",
    val scope: String = "all",
    val dryRun: Boolean = false,
    val confirm: Boolean = false,
)

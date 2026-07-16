# VModal Android SDK quick reference

Package: `com.vmodal.sdk`

Maven: `com.vmodal:vmodal-sdk-android:1.0.0`

Use this page after completing the root [quick start](README.md). It indexes the
SDK's public entrypoints; it is not intended to be read from top to bottom.
Optional parameters and defaults are abbreviated where the full declaration
would make the index harder to scan. Use IDE completion for every constructor
property.

## Find the right starting point

| I want to... | Start with |
|---|---|
| Connect an Android app with an API token | [`README.md` quick start](README.md#quick-start-get-your-first-api-response) |
| Confirm identity and API availability | `client.auth.me()` and `client.health()` |
| See the user's collection names | `client.collections.listGroups()` |
| Search video content | `client.searches.searchVideo()` |
| Upload an Android content URI | [`docs/sdk_doc.md`](docs/sdk_doc.md#step-1-convert-a-content-uri-to-an-upload-source) |
| Run a durable background upload | [`docs/sdk_doc.md`](docs/sdk_doc.md#step-4-move-long-uploads-to-workmanager) |
| Copy a focused example | [`examples/01_starter/`](examples/01_starter/) |

Unless a method name ends in `Async`, assume it is blocking and call it from
`Dispatchers.IO`, WorkManager, or another worker thread.

## Client and configuration

For most Android apps, create a gateway client from a runtime token and resolve
the identity with `auth.me()` as shown in the [quick start](README.md). Direct
mode is for environments where the caller already has trusted identity values.

| Entrypoint | Purpose |
|---|---|
| `Client(cfg, transport, signedUploads)` | Construct from an `SdkConfig`, with optional injectable transports. |
| `Client(baseUrl, userId, tenantId, email, token, timeoutMillis, maxRetries, ...)` | Construct an authenticated gateway client from explicit values. |
| `Client.fromEnv(env, transport, signedUploads, resolveIdentity)` | Construct from environment values and optionally resolve `auth.me()`. |
| `Client.unsafeDirect(baseUrl, userId, ...)` | Explicit trusted-network escape hatch; never use as a public identity boundary. |
| `client.cfg` / `client.http` | Effective configuration and low-level HTTP client. |
| `client.auth` | `AuthResource`. |
| `client.searches` | `SearchesResource`. |
| `client.collections` | `CollectionsResource`. |
| `client.indexes` | `IndexesResource`. |
| `client.admin` | `AdminResource`. |
| `client.r2` | `R2Resource`. |
| `client.images` | `ImagesResource`. |
| `client.gdrive` / `client.sql` | Disabled resource placeholders. |
| `client.health()` | Shortcut for `client.auth.health()`. |
| `client.authCheck(userId)` | Shortcut for `client.auth.authCheck(userId)`. |

Configuration declarations:

- `SdkConfig(...)` and `SdkConfig.fromEnv(...)`
- `PUBLIC_GATEWAY_URL`, `DEV_GATEWAY_URL`, and `VMODAL_SDK_VERSION`
- `strGatewayBaseUrl(baseUrl, mode)` and `strUsersBaseUrl(baseUrl)`

`SdkConfig.fromEnv()` recognizes `VMODAL_ENV`, `VMODAL_BASE_URL`,
`VMODAL_API_KEY`, `VMODAL_API_TOKEN`, `VMODAL_USER_ID`, `VMODAL_TENANT_ID`,
`VMODAL_USER_EMAIL`, `VMODAL_TIMEOUT`, and `VMODAL_MAX_RETRIES`. Test aliases
present in the repository environment are also supported.

## Auth

| Entrypoint | Returns | Purpose |
|---|---|---|
| `client.auth.health()` | `HealthResponse` | Check the search API health endpoint. |
| `client.auth.authCheck(userId = "")` | `Boolean` | Verify that a health request succeeds for the effective user. |
| `client.auth.me()` | `UserProfile` | Resolve the bearer token owner through users_api. |

## Search

| Entrypoint | Returns |
|---|---|
| `client.searches.searchVideo(request: SearchRequest)` | `SearchResponse` |
| `client.searches.searchVideo(queryText, queryMetadata, imageQuery, mode, groupName, streamName, searchSources, searchCombineMode, startDate, endDate, offset, limit, textEmbScoreMin, imageEmbScoreMin, versionLancedb)` | `SearchResponse` |

`SearchRequest` exposes `validate()` and `toMap()`.

## Collections

| Entrypoint | Returns | Notes |
|---|---|---|
| `client.collections.listGroups(mode = null)` | `GroupsResponse` | List accessible collection groups. |
| `client.collections.uploadFile(part, groupName, mode, streamName, description, tag)` | `UploadResponse` | Legacy multipart helper for small files. |
| `client.collections.uploadFile(file, groupName, mode, streamName, description, tag)` | `UploadResponse` | `File` overload of the legacy helper. |
| `client.collections.uploadMetadataJsonl(part, mode, groupName, streamName, writeMode, allowOverlap)` | `MetadataParquetUploadResponse` | Upload JSONL metadata. |
| `client.collections.addAssets(collectionId, assetIds, mode, groupName, streamName)` | `CollectionAddAssetsResponse` | Add assets to a collection. |
| `client.collections.updateDescription(groupName, mode, streamName, filenameSanitized, description, tag)` | `CollectionDescriptionUpdateResponse` | Update an asset description and/or tags. |
| `client.collections.delete(groupName, mode, scope, dryRun, confirm)` | `DeleteCollectionResponse` | Dry-run or confirm collection deletion. |

Disabled methods deliberately throw `FeatureDisabled`: `uploadFolder()`,
`create()`, `edit()`, `autoIndexGet()`, and `autoIndexSet()`.

**DEPRECATED:** `/collection/upload/google_drive` is retained only as a route
constant for upstream parity and is not exposed by `CollectionsResource`.

## Signed video uploads

These are extension functions on `CollectionsResource`; import them explicitly
outside the `com.vmodal.sdk` package. Beginners should use
`videoUploadAsync()` with default options first, then add resume or adaptive
settings from the [upload guide](docs/sdk_doc.md).

| Entrypoint | Behavior |
|---|---|
| `collections.videoUploadAsync(source, collectionName, subCollectionName, mode, modality, ttl, options, onProgress, onSuccess, onFailure)` | Non-blocking, cancelable upload; returns `UploadHandle`. |
| `collections.videoUpload(source, collectionName, subCollectionName, mode, modality, ttl, options, onProgress)` | Blocking upload for workers/tests; returns `VideoUploadResponse`. |
| `collections.videoUploadBulk(sources, collectionName, subCollectionName, mode, modality, ttl, options)` | Blocking sequential bulk upload; returns `VideoUploadBulkResponse`. |
| `collections.videoUploadBulkAsync(sources, collectionName, subCollectionName, mode, modality, ttl, options, onProgress, onSuccess, onFailure)` | Non-blocking bulk upload; returns `UploadHandle`. |

Upload types and helpers:

- `UploadSource(...)`, `UploadSource.fromFile(file, contentType)`, and
  `source.open(offset, length)`
- `VideoUploadOptions(...)`, `options.resolvedFor(size)`, and
  `options.validate(size)`
- `UploadProgress(uploadedBytes, totalBytes)` with `percent`
- `UploadHandle.isCanceled` and `UploadHandle.cancel()`
- `UploadSessionStore.load/save/remove`
- `MemoryUploadSessionStore`, `FileUploadSessionStore`, and
  `UploadSessionStores.memory`
- `SignedUploadTransport.enqueue(...)` and `OkHttpSignedUploadTransport(...)`
- `SignedUploadResult(statusCode, etag, localMd5)`

`VideoUploadOptions()` always selects the single signed-URL flow. Setting
`multipart = true` is an experimental opt-in for gateways that expose every
multipart route; missing capability fails with `FeatureDisabled`.

Adaptive policy entrypoints:

- `UploadConditions(networkType, networkSpeed, deviceMemory)`
- `UploadNetworkType`: `WIFI`, `CELLULAR`, `UNKNOWN`
- `UploadNetworkSpeed`: `SLOW`, `STANDARD`, `FAST`, `UNKNOWN`
- `UploadDeviceMemory`: `LOW`, `STANDARD`, `HIGH`
- `AdaptiveUploadPolicy.select(sizeBytes, conditions)` returns
  `AdaptiveUploadPreset`

## Index lifecycle

| Entrypoint | Returns |
|---|---|
| `client.indexes.jobsList(status, mode, groupName, limit)` | `IndexationJobsListResponse` |
| `client.indexes.createIndex(request: IndexationSubmitRequest)` | `IndexationSubmitResponse` |
| `client.indexes.createIndex(mode, groupName, indexType, modality, streamName, insertMode, createIndex, version, startDate, endDate, embeddingModel, reProcess, dryRun)` | `IndexationSubmitResponse` |
| `client.indexes.indexStatus(jobId)` | `IndexationStatusResponse` |
| `client.indexes.deleteIndex(request: IndexationDeleteRequest)` | `IndexationDeleteResponse` |
| `client.indexes.deleteIndex(mode, groupName, version, modality, dryRun, confirm)` | `IndexationDeleteResponse` |

`client.indexes.embeddingModels()` is present for compatibility and throws
`FeatureDisabled`.

## Admin and usage

| Entrypoint | Returns |
|---|---|
| `client.admin.userStats()` | `AdminUserStatsResponse` |
| `client.admin.usage(date = "")` | `UsageUserDetail` |
| `client.admin.cacheStats()` | `CacheStats` |

## R2 helpers

| Entrypoint | Returns |
|---|---|
| `client.r2.presignUploadFile(mode, groupName, streamName, modality, filename, expiresIn)` | `PresignedUploadResponse` |
| `client.r2.presignUploadFolderVideo(mode, groupName, streamName, filenames, expiresIn)` | `PresignedFolderResponse` |

Prefer the signed `videoUpload*` entrypoints for video uploads; they manage PUT,
multipart upload, retries, completion, and resume for the caller.
Presign helpers default to 900 seconds. Raw R2 session credentials are not part
of the public mobile SDK surface.

## Images

| Entrypoint | Returns |
|---|---|
| `client.images.getUrl(mode, groupName, modality, filename, streamName, tsUnix13digits, userid)` | `ImageUrlResponse` |
| `client.images.getUrlBulk(records, userid)` | `ImageUrlBulkResponse` |
| `client.images.getImageFromUrl(urlPreSigned, userid)` | `ByteArray` |
| `client.images.getImageBulkFromUrls(urls, userid)` | `ImageGetBulkResponse` |

`ImageRecord.toMap()` and `ImageUrlRecord.toMap()` build bulk request records.

## Disabled resources

The following callable compatibility entrypoints always throw
`FeatureDisabled` because the server routes are inactive:

- `client.gdrive.privateAuthUrl()`
- `client.gdrive.privateDownload()`
- `client.sql.query()`

## Request and response models

Request/data models:

- `SearchRequest`
- `DeleteCollectionRequest`
- `CollectionAddAssetsRequest`
- `IndexationSubmitRequest`
- `IndexationDeleteRequest`
- `ImageRecord`
- `ImageUrlRecord`

Each request type that has required fields exposes `validate()`; all request
types above expose `toMap()`.

All typed responses inherit `JsonBackedResponse` and expose `raw`. Response
classes are:

- Auth/search/collections: `HealthResponse`, `UserProfile`, `SearchResponse`,
  `GroupsResponse`, `UploadResponse`, `MetadataParquetUploadResponse`,
  `CollectionDescriptionUpdateResponse`,
  `DeleteCollectionResponse`, and `CollectionAddAssetsResponse`
- Signed uploads: `ExternalUploadSignedUrlResponse`, `MultipartPart`,
  `MultipartSignedPart`, `MultipartCreateResponse`, `MultipartSignResponse`,
  `MultipartStatusResponse`, `MultipartCompleteResponse`,
  `VideoUploadResponse`, and `VideoUploadBulkResponse`
- Index/admin: `IndexationJobsListResponse`, `IndexationSubmitResponse`,
  `IndexationStatusResponse`, `IndexationDeleteResponse`,
  `AdminUserStatsResponse`, `UsageUserDetail`, and `CacheStats`
- R2/images: `PresignedUploadResponse`,
  `PresignedFolderItem`, `PresignedFolderResponse`, `ImageUrlResponse`,
  `ImageUrlBulkResponse`, `ImageGetBulkResponse`, `ImageResponse`,
  `FullPathImageResponse`, and `ImageBulkResponse`
- Raw item wrappers: `SearchResultItem`, `GroupItem`, `FolderUploadItem`,
  `CollectionAsset`, `IndexationJobItem`, and `AdminUserStatItem`

`FolderUploadResponse` remains public for source compatibility even though
folder upload is disabled.

## Errors

All SDK exceptions derive from `SdkError(message, statusCode, body, details)`:

- `AuthError`: ask the user to sign in again or provide a valid token.
- `ValidationFailed`: correct the request input; the server may have returned
  HTTP 422.
- `ApiError`: show a retry action when appropriate and keep the status/body for
  diagnostics.
- `FeatureDisabled`: do not retry; that compatibility entrypoint is
  intentionally unavailable.

## Low-level transport and utilities

Most applications should use `Client`, but these public hooks support tests and
custom integrations:

- `VmodalTransport.execute(request)` and `HttpUrlConnectionTransport(cfg)`
- `VmodalRequest` and `VmodalResponse`; `response.json` and
  `response.jsonObject()`
- `VmodalHttp.headers()`, `request()`, `requestBytes()`, and `requestUsers()`
- `VmodalFilePart`, `filePart(...)`, `streamPart(...)`, and
  `guessContentType(name)`
- `VmodalJson.stringify(value)` and `VmodalJson.parse(text)`
- `Routes`, `Routes.Endpoints`, `Routes.UsersEndpoints`, `Routes.full(path)`,
  and `Routes.usersFull(path)`
- `Routes.PREFIX`, `Routes.USERS_API_PREFIX`, signed-upload route constants,
  `Routes.activeEndpoints`, and `Routes.disabledEndpoints`

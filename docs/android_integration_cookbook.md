# Android integration cookbook

This cookbook maps application tasks to the VModal Android SDK's public API.
New Kotlin code should use `Client.coroutines()` from an application-owned
scope. Existing blocking and callback consumers remain supported, but blocking
calls must never run on Android's main thread.

> **UI ownership:** VModal is a UI-free client library. Your application owns
> navigation, state presentation, accessibility, lifecycle collection,
> theming, and every design-system choice. The
> [Compose search demo](../examples/02_search/README.md) and
> [staged full app](../examples/03_fullapp/README.md#fullapp-demo) are downstream
> consumer examples, not reusable UI components or a supported design system.

All complete cookbook sources live in the
[compile-checked starter project](../examples/01_starter/README.md). Short code
fragments below explain decisions; the linked Kotlin files are canonical and
are compiled with Android API 34, Java 17, strict dependency verification, no
credential, and no network calls.

![VModal Android application and searchable media screens](../assets/dev_homepage.jpg)

## Choose an integration path

- For Compose, retain a session-scoped `Client`, launch in `viewModelScope`,
  expose immutable `StateFlow`, and collect with `collectAsStateWithLifecycle`.
  See the compile-tested
  [SearchViewModel](../examples/02_search/app/src/main/kotlin/com/vmodal/sdk/examples/search/SearchViewModel.kt).
- For classic Views, give an Activity/Fragment-independent controller a
  lifecycle-owned scope and close it during teardown. See
  [ClassicAndroidIntegration.kt](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/ClassicAndroidIntegration.kt).
- For a picker URI, validate a stable byte length and return a source that can
  reopen the URI. See
  [ContentUriUploadSource.kt](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/ContentUriUploadSource.kt).
- For work that must outlive a screen, persist only account/scope/URI
  identifiers and use `CoroutineWorker`. See
  [VmodalUploadWorker.kt](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/VmodalUploadWorker.kt).

The [generated Kotlin API reference](../docs_sdk/index.html) is the signature
authority. The source SDK remains the behavior authority.

## Capability map

Each row states the public entry point, execution model and result, coupled
inputs, normal empty/error outcomes, lifecycle owner, and canonical source.
Unless noted otherwise, public operations preserve typed `SdkError` failures.

| Task | Public API and execution | Contract and outcomes | Android owner and canonical source |
|---|---|---|---|
| Authenticate | `Client(SdkConfig)` then `coroutines().auth.me(): UserProfile`; suspending and cancellable | Inject a runtime bearer through `MutableApiKeyProvider`. `AuthError` means reauthenticate. | Authenticated-session owner; [gateway client](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/01_create_gateway_client.kt) |
| Resolve identity and health | `CoroutineAuthResource.me(): UserProfile`, `health(): HealthResponse`; suspending | A successful profile must contain the mapped user. Health is availability, not authorization. | Session bootstrap; [identity and health](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/03_identity_and_health.kt) |
| Rotate a key | `MutableApiKeyProvider.rotate()`, `clear()`, `close()`; synchronous | Rotation affects the next request without rebuilding a same-identity client. A cleared provider throws `AuthError` before transport. | Session credential owner; [rotation source](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/03_rotate_api_key.kt) |
| Discover collections | `CoroutineCollectionsResource.listGroups(): GroupsResponse`; suspending | Filter by `mode`; an empty `data` list is valid. Select only a returned group for the current credential. | ViewModel/repository; [list groups](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/06_list_groups.kt) |
| Upload a small file | `CoroutineCollectionsResource.uploadFile(): UploadResponse`; suspending compatibility API | Keep `mode`, `groupName`, and `streamName` coupled. Intended for small multipart-form uploads, not large video. | Caller-owned coroutine; [small upload](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/07_small_file_upload.kt) |
| Upload video | `videoUpload(): VideoUploadResponse` or `videoUploadEvents(): Flow<VideoUploadEvent>`; suspending/cold Flow | Requires a reopenable `UploadSource` with exact length plus collection/stream/mode. Each Flow collection starts one upload. | ViewModel/repository/worker; [coupled workflow](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/CookbookWorkflow.kt) |
| Cancel upload | Cancel the collecting `Job`, or callback `UploadHandle.cancel()` | Cancellation reaches cancellable HTTP and signed-part calls. Do not convert `CancellationException` to retry or failure UI. | Owning feature; [classic controller](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/ClassicAndroidIntegration.kt) and [callback cancellation](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/10_cancel_upload.kt) |
| Resume multipart | `VideoUploadOptions(multipart=true, resume=true, sessionStore=...)` | Experimental and gateway-dependent. The same source identity, size, mode, collection, stream, modality, and part size must be restored. `FeatureDisabled` is terminal. | Account-scoped worker; [resumable upload](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/12_resumable_upload.kt) |
| Create and poll an index | `createIndex(): IndexationSubmitResponse`, `indexStatus(): IndexationStatusResponse`; suspending | Submission is not readiness. Retain the returned job ID and poll to a documented success/failure state with a finite bound. | ViewModel or worker job; [bounded poll](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/CookbookWorkflow.kt) |
| Search video | `CoroutineSearchesResource.searchVideo(): SearchResponse`; suspending | Keep mode/collection/stream/version together. Zero rows is success. Validation and request failures are separate states. | ViewModel/repository; [filtered search](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/05_filtered_search.kt) |
| Map hits to image URLs | `CoroutineImagesResource.getUrlBulk(): ImageUrlBulkResponse`; suspending | Skip hits lacking a filename/stream coordinate; join returned records by `input_index`, never list position. Partial resolution is valid. | Search repository; [coupled mapping](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/CookbookWorkflow.kt) |
| Fetch bounded image bytes | `getImageFromUrl(): ByteArray`, `getImageBulkFromUrls(): ImageGetBulkResponse`; suspending | Responses are bounded and may throw `ResponseTooLarge`. Never log or persist signed URLs or raw image responses. | Image/data layer; [image access](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/19_image_access.kt) |
| Read admin data | `CoroutineAdminResource.userStats()`, `usage()`, `cacheStats()`; suspending | Requires account permission. Empty counters are data; `AuthError`/`ApiError` are failures. | Privileged admin feature; [admin source](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/20_admin_and_r2.kt) |
| Presign R2 uploads | `CoroutineR2Resource.presignUploadFile()` and `presignUploadFolderVideo()`; suspending | Keep mode/collection/stream/object names coupled. Presign mutations are not automatically retried. Never log signed results. | Privileged upload repository; [R2 source](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/20_admin_and_r2.kt) |

Unavailable compatibility methods fail locally with `FeatureDisabled`: folder
upload, collection create/edit/auto-index, embedding-model discovery, GDrive,
and SQL. Do not implement client-side route or transport workarounds for them.

## Minimal upload → index → search → image recipe

The canonical implementation is
[CookbookWorkflow.kt](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/CookbookWorkflow.kt).
It uses one immutable `CookbookScope`:

```kotlin
val scope = CookbookScope(
    mode = "vid_file",
    collection = selectedCollection,
    stream = selectedStream,
    searchSources = listOf("ocr", "asr", "image"),
)
```

Do not let a mutable screen field independently choose coordinates at each
stage. A scope change invalidates upload progress, job ID, selected version,
search rows, and resolved images from the prior scope.

### 1. Authenticate and choose a collection

Create a gateway `Client` with a runtime `MutableApiKeyProvider`, resolve
`auth.me()`, then retain the identity-enriched client for that authenticated
session. Call `listGroups("vid_file")` before selecting an existing collection.
An empty list means the account currently has no visible video collections; it
does not mean transport or authentication failed. Upload may create the chosen
collection implicitly.

### 2. Create a reopenable upload source

For `content://`, query `OpenableColumns.DISPLAY_NAME` and `SIZE`, fall back to
an asset-file descriptor length, and reject unknown or zero length before
signing. The source opener must call `ContentResolver.openInputStream()` each
time; do not retain one consumed stream.

Take persistable read permission only when the picker provider and contract
grant it and background reopening is required. The compile-checked adapter is
[ContentUriUploadSource.kt](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/ContentUriUploadSource.kt).

### 3. Upload and verify completion

Call `uploadCookbookVideo(client, scope, source)`. Completion means the bytes
were uploaded and the SDK's upload-finalization response completed. Verify the
typed result (`uploaded`, filename/destination as required by your app). It does
not mean an index exists or that search is ready.

For progress UI, collect `videoUploadEvents()` once in the owning repository or
ViewModel. If several screens need the result, expose a shared app-owned
`StateFlow`; collecting the SDK's cold Flow in every screen starts duplicate
uploads.

### 4. Submit one index job

Call `submitCookbookIndex(client, scope)`. Preserve the returned `jobId` with
the immutable scope. A response such as `queued` or `running` confirms only
submission.

### 5. Poll to a terminal state

Call `awaitCookbookIndex(client, jobId, maxPolls, pollMillis)` from a cancellable
scope. The canonical fixture treats `success`, `succeeded`, `done`, `completed`,
and `ok` as ready, and `failed`, `failure`, `error`, `cancelled`, `canceled`, and
`dead_letter` as failed. Unknown nonterminal values remain pending until the
finite poll bound expires. Your service contract should remain the authority
if its terminal vocabulary changes.

### 6. Select the advertised searchable version

After terminal success, list the same collection again and use
`GroupItem.latestLancedbVersion` when the app wants the latest advertised
numeric `vN` version. Historical version selection is an explicit application
decision; never silently substitute an unrelated default. The canonical helper
returns `ReadyCookbookScope(scope, lancedbVersion)` so the version cannot drift
away from its collection coordinates.

### 7. Search explicitly

Call `searchCookbookVideo()` with the ready scope:

- `searchSources` selects OCR text, ASR speech, image similarity, or an explicit
  subset. Do not infer sources from the query text.
- `searchCombineMode="union"` asks the service to combine matches from any
  selected source; use `intersection` only when the backend contract and the
  desired product behavior require matches across sources. Ranking remains
  backend-owned.
- `queryMetadata` is a metadata filter, while `startDate`/`endDate` bound dates.
  Keep them separate from the natural-language query.
- `textEmbScoreMin` and `imageEmbScoreMin` apply independent source thresholds.
  Tune them with representative data rather than treating scores as universal.
- `offset` and `limit` define one page. Preserve the original search rank when
  images are filtered or partially resolved.
- `versionLancedb` must be the version advertised for the selected collection.

`SearchResponse.data.isEmpty()` is a normal **empty** state. An exception is a
**request failure**. Rows returned but removed by app filters are a third
**filtered-out** state.

### 8. Resolve result images without corrupting rank

Build an `ImageUrlRecord` only when a hit contains the fields required by the
image contract. Count skipped hits rather than inventing blank filenames or
timestamps. Send the candidate records in one `getUrlBulk()` call, then map
each response's `input_index` back to the candidate that originated it. Reject
out-of-range and duplicate indexes.

Keep these UI states separate:

- zero backend matches;
- matches present but all missing required image coordinates;
- some image URLs resolved and some unresolved;
- the entire bulk image request failed.

The full app has deterministic tests for this join and partial results; see the
[search and image section](../examples/03_fullapp/README.md#fullapp-search).

## Lifecycle recipes

### Compose and ViewModel

Retain only app state in a ViewModel, launch suspend calls in `viewModelScope`,
and expose immutable `StateFlow`. Compose collects with
`collectAsStateWithLifecycle`; a View-based screen can use
`repeatOnLifecycle`. The SDK owns neither scope nor dispatcher. Increment a
session or request generation whenever identity/scope changes and reject a late
result whose generation no longer matches.

The [Compose SearchViewModel](../examples/02_search/app/src/main/kotlin/com/vmodal/sdk/examples/search/SearchViewModel.kt)
and [full-app ViewModel](../examples/03_fullapp/app/src/main/kotlin/com/vmodal/sdk/examples/fullapp/FullAppViewModel.kt)
are consumer smoke examples. Their navigation, state shape, strings, loading
indicators, accessibility, theme, and design system belong to the app.

### Classic Android

Keep SDK ownership outside the Activity or Fragment. Inject a lifecycle-owned
scope into a controller, retain callback `UploadHandle` values there, and call
`close()` during teardown. SDK callback upload events arrive off the main
thread; marshal UI changes through the injected lifecycle scope. The complete
pattern is [ClassicAndroidIntegration.kt](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/ClassicAndroidIntegration.kt).

### `content://`

Store the URI identifier, not an Activity, View, open stream, or filesystem
path. Enforce known length before signed upload. For work surviving process
death, request and retain a persistable read grant, then reopen the URI in the
worker. A provider denial, expired grant, missing name, or unknown length is a
terminal application state until the user selects the source again; it is not
an infinite retry condition.

### WorkManager

Schedule constrained work with an account-specific unique name/tag. Persist
only the account identifier, URI, mode, collection, stream, and file metadata;
obtain the current client through application-owned dependency injection. The
worker must verify the current account still matches before reopening the URI.

[VmodalUploadWorker.kt](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/VmodalUploadWorker.kt)
rethrows cancellation, bounds attempts, and requires an application
reconciliation hook before replaying a transient ambiguous upload. It never
retries `AuthError`, `ValidationFailed`, `FeatureDisabled`, malformed or
oversized responses, URI permission failures, or deterministic API errors.

## Typed failure decision tree

Handle structure, not a single error string. Never print credentials, signed
URLs, `SdkError.body`, raw responses, or exception payloads.

| Failure | Application action | Retry policy |
|---|---|---|
| `AuthError` | Stop account work, refresh/re-authenticate, then validate identity again. | No blind replay, especially for mutations. |
| `ValidationFailed` | Correct local input and preserve the user's editable state. | No retry with unchanged input. |
| `FeatureDisabled` | Hide/disable the unsupported feature for this gateway. | Never retry. |
| `TransportError` | Show an offline/transient state. Reconcile mutations before replay. | Bounded retry for safe reads; mutation retry only after reconciliation. |
| `ResponseTooLarge` | Use a bounded/lower-volume operation or reduce the requested page/batch. | No identical retry. |
| `MalformedResponse` | Report a redacted service-contract failure. | No automatic retry unless the operation is a safe read under an explicit policy. |
| `ApiError` | Branch on status and operation semantics; keep status separate from empty data. | Safe reads may use a bounded transient-status policy. Mutations require reconciliation. |
| Other `SdkError` | Show a redacted typed failure and retain recoverable input. | No default retry. |
| `CancellationException` | End the owned operation silently or as an explicit canceled state. | Rethrow; never retry. |

## Credential rotation, logout, and account switch

The application acquires a user-scoped, revocable credential at runtime and
injects it through `MutableApiKeyProvider`. Never put it in source, resources,
`BuildConfig`, Gradle properties, `local.properties`, manifests, saved instance
state, WorkManager input, logs, screenshots, or deep links.

For a same-user key refresh, call `provider.rotate(newKey)`. The retained client
reads the new key on its next request. For logout, tenant change, or account
switch, use one ordered cleanup sequence:

1. Increment the app's session generation so old callbacks cannot publish.
2. Cancel ViewModel/repository jobs and every retained `UploadHandle`.
3. Cancel WorkManager work tagged for the old account and discard resumable
   checkpoints owned by that account.
4. Release app-owned persisted URI grants that should not cross sessions.
5. Call `MutableApiKeyProvider.close()` (or `clear()`) so new authenticated
   requests fail before transport.
6. Drop retained `Client` and coroutine facade references.
7. Clear selected URIs, collection/version choices, search rows, image URLs,
   progress, errors containing account context, and other in-memory UI state.
8. Acquire a new credential and build a new authenticated session only after
   the new account is established.

See [Mobile credential lifecycle](manage_api_key.md) for the production
boundary and [credential rotation source](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/03_rotate_api_key.kt)
for the compile-checked provider calls.

## Validate the cookbook

From `uinterface/sdk_android`:

```bash
examples/02_search/gradlew -p examples/01_starter --no-daemon \
  --dependency-verification strict :compileDebugKotlin
python docs.py check_links
bash test.sh all
```

These checks require no `VMODAL_API_KEY` and make broken repository-relative
links, missing canonical sources, stale SDK method names, and uncompilable
Android lifecycle examples fail locally and in pull-request validation.

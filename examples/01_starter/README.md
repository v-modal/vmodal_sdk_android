# VModal Android SDK starter examples

This directory is a task-oriented catalog of small Kotlin examples for the
VModal Android SDK. Each file demonstrates one focused SDK operation so that
you can copy the relevant function into an Android application, ViewModel,
repository, or worker without adopting a sample application architecture.

Every Kotlin file under
[`src/main/kotlin/com/vmodal/sdk/examples/`](src/main/kotlin/com/vmodal/sdk/examples/)
is a canonical, compile-checked source. This directory is a standalone Android
library-style project: it has no launcher or live network task, but it verifies
the snippets against the current source SDK, Android API 34, and Java 17.

From `uinterface/sdk_android`, compile the catalog without a credential:

```bash
examples/02_search/gradlew -p examples/01_starter --no-daemon \
  --dependency-verification strict :compileDebugKotlin
```

For runnable UI consumers, use the [Compose search example](../02_search/README.md)
or the [staged full application](../03_fullapp/README.md). Those apps demonstrate
consumer patterns; your application still owns navigation, UI state,
accessibility, theming, and its design system.

## What you can learn here

The examples cover the complete client workflow:

- create a gateway client and bind it to the authenticated identity;
- rotate a runtime API key without rebuilding a same-user client;
- run simple and filtered multimodal searches;
- inspect collection groups before choosing search or upload coordinates;
- upload files, Android `content://` URIs, metadata, and batches;
- cancel UI-owned uploads or run blocking uploads from WorkManager;
- opt into experimental resumable or adaptive multipart uploads;
- mutate collections and manage index lifecycle operations;
- resolve image URLs and download image bytes;
- inspect privileged admin and R2 information.

## Prerequisites

Before copying a snippet, complete the SDK [root quick start](../../README.md).
Your host application needs:

| Requirement | Reference value |
|---|---|
| Android minimum SDK | API 24 |
| Android compile/target SDK | API 34 in the reference app |
| Java toolchain and JVM target | Java 17 |
| Kotlin | 1.9.24 in the reference build |
| Network permission | `android.permission.INTERNET` |
| SDK dependency | `com.vmodal:vmodal-sdk-android:1.0.0` or the source project |
| Runtime access | A user-scoped VModal API key and existing data for read examples |

Add the network permission to the host application's manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

During local SDK development, include the source checkout from the parent
application's `settings.gradle.kts`:

```kotlin
include(":vmodal-sdk-android")
project(":vmodal-sdk-android").projectDir = file("../vmodal_sdk_android")
```

Then add the project dependency:

```kotlin
dependencies {
    implementation(project(":vmodal-sdk-android"))
}
```

If the artifact is available in your configured repository, use its Maven
coordinate instead:

```kotlin
dependencies {
    implementation("com.vmodal:vmodal-sdk-android:1.0.0")
}
```

## Before you copy a snippet

Replace all illustrative values with coordinates from your own account. The
examples deliberately use names such as `field-tests`, `traffic-cameras`,
`astream`, and `camera-01.mp4`; those resources are not created automatically.

The most commonly repeated parameters are:

| Parameter | Meaning | Example value |
|---|---|---|
| `mode` | Data family queried or mutated | `vid_file` or `img_file` |
| `groupName` / `collectionName` | Top-level logical collection | `field-tests` |
| `streamName` / `subCollectionName` | Stream below the collection | `astream` |
| `modality` | Stored representation | `vid_raw` or `image` |
| `searchSources` | Signals included in search | `ocr`, `asr`, `image` |
| `filename` | Stored or source filename | `camera-01.mp4` |

Parameter names differ slightly between API families because the SDK preserves
the corresponding server contracts. Do not assume that `groupName` and
`collectionName`, or `streamName` and `subCollectionName`, are interchangeable
named arguments in Kotlin even when they refer to the same logical coordinate.

## Threading and lifecycle rules

Prefer `client.coroutines()` from a caller-owned `viewModelScope`,
`lifecycleScope`, or `CoroutineWorker`. The facade suspends blocking transport
work away from the main thread and propagates lifecycle cancellation. The
blocking resource methods remain available for workers and existing callers,
but must never run on Android's main thread.

The upload extensions have two deliberate execution styles:

- `videoUploadAsync()` and `videoUploadBulkAsync()` start SDK-owned background
  work and return an `UploadHandle` immediately.
- `videoUpload()` blocks until it succeeds or fails and is intended for a
  worker or test.

Async progress, success, and failure callbacks also run away from the Android
main thread. Switch to `Dispatchers.Main` before changing Views or UI-owned
state. Retain the returned `UploadHandle` while an upload is active so that a
user action or lifecycle policy can cancel it.

A typical ViewModel integration is deliberately small:

```kotlin
viewModelScope.launch {
    val result = sdk.coroutines().searches.searchVideo(request)
    // Publish immutable UI state; lifecycle-aware UI collection is app-owned.
}
```

The complete coupled recipe is
[`CookbookWorkflow.kt`](src/main/kotlin/com/vmodal/sdk/examples/CookbookWorkflow.kt).

## Credential and client model

Use gateway mode for ordinary mobile applications. The application obtains a
user-scoped, revocable API key from its authenticated backend and injects it at
runtime. Do not embed a real key in source, `BuildConfig`, resources, Gradle
properties, `local.properties`, the manifest, logs, or deep links.

The recommended client startup flow is:

1. Load an already-issued runtime key using an app-owned credential policy.
2. Create a bootstrap gateway client.
3. Call `client.coroutines().auth.me()` from a caller-owned scope.
4. Copy the returned user, tenant, and email into the retained client config.
5. Keep the client and its `MutableApiKeyProvider` at authenticated-session or
   application scope.
6. Rotate the provider for a replacement key belonging to the same identity.
7. On logout or account/tenant change, invalidate stale callbacks, cancel work
   and uploads, cancel account-tagged WorkManager jobs, clear URI grants and
   result state, close the provider, discard the client, and build a new
   session.

`Client.unsafeDirect(...)` is intentionally separate. It is only for a trusted
private network where the downstream service independently authenticates and
authorizes the supplied identity. Do not expose direct mode to an untrusted
network and do not use it as a shortcut around gateway authentication.

For the complete mobile credential contract, read
[Mobile credential lifecycle](../../docs/manage_api_key.md).

## Recommended learning path

### Stage 1: prove authentication and connectivity

1. Create the preferred gateway client with
   [`01_create_gateway_client.kt`](src/main/kotlin/com/vmodal/sdk/examples/01_create_gateway_client.kt).
2. If the app keeps a long-lived authenticated session, adopt key rotation from
   [`03_rotate_api_key.kt`](src/main/kotlin/com/vmodal/sdk/examples/03_rotate_api_key.kt).
3. Confirm identity and service health with
   [`03_identity_and_health.kt`](src/main/kotlin/com/vmodal/sdk/examples/03_identity_and_health.kt).
4. List available groups using
   [`06_list_groups.kt`](src/main/kotlin/com/vmodal/sdk/examples/06_list_groups.kt).

At this point, dependency setup, network permission, authentication, and basic
data access should all be working.

### Stage 2: search existing content

5. Run a minimal text query with
   [`04_text_search.kt`](src/main/kotlin/com/vmodal/sdk/examples/04_text_search.kt).
6. Add metadata, date, score, and multimodal controls with
   [`05_filtered_search.kt`](src/main/kotlin/com/vmodal/sdk/examples/05_filtered_search.kt).
7. Resolve returned frame coordinates to images using
   [`19_image_access.kt`](src/main/kotlin/com/vmodal/sdk/examples/19_image_access.kt).

### Stage 3: upload from Android

8. Convert a picker URI into a reopenable source with
   [`ContentUriUploadSource.kt`](src/main/kotlin/com/vmodal/sdk/examples/ContentUriUploadSource.kt).
9. Start a UI-owned upload with
   [`09_async_video_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/09_async_video_upload.kt).
10. Retain and cancel its handle as shown in
    [`10_cancel_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/10_cancel_upload.kt).
11. Move long-lived work into WorkManager using
    [`VmodalUploadWorker.kt`](src/main/kotlin/com/vmodal/sdk/examples/VmodalUploadWorker.kt).

### Stage 4: add advanced data operations

12. Upload batches or JSONL metadata.
13. Mutate collections and create indexes.
14. Add experimental multipart behavior only if the selected gateway exposes
    the complete multipart route family.
15. Use admin and R2 calls only for accounts with the required permissions.

## Complete example index

| File | Purpose | Important precondition |
|---|---|---|
| [`01_create_gateway_client.kt`](src/main/kotlin/com/vmodal/sdk/examples/01_create_gateway_client.kt) | Bootstrap gateway auth and retain resolved identity | Valid runtime API key |
| [`02_create_direct_client.kt`](src/main/kotlin/com/vmodal/sdk/examples/02_create_direct_client.kt) | Construct an explicitly unsafe direct client | Trusted private downstream only |
| [`03_identity_and_health.kt`](src/main/kotlin/com/vmodal/sdk/examples/03_identity_and_health.kt) | Print current identity and service health | Configured client |
| [`03_rotate_api_key.kt`](src/main/kotlin/com/vmodal/sdk/examples/03_rotate_api_key.kt) | Load, rotate, and clear an app-owned credential | Same-user replacement keys |
| [`04_text_search.kt`](src/main/kotlin/com/vmodal/sdk/examples/04_text_search.kt) | Run a minimal video text search | Indexed video collection |
| [`05_filtered_search.kt`](src/main/kotlin/com/vmodal/sdk/examples/05_filtered_search.kt) | Search with metadata, sources, dates, and score threshold | Matching indexed metadata and date format |
| [`06_list_groups.kt`](src/main/kotlin/com/vmodal/sdk/examples/06_list_groups.kt) | Enumerate video collection groups | Read access |
| [`07_small_file_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/07_small_file_upload.kt) | Use the legacy multipart-form upload endpoint | Small local file |
| [`08_content_uri_source.kt`](src/main/kotlin/com/vmodal/sdk/examples/08_content_uri_source.kt) | Adapt an Android URI to a reopenable stream | URI permission and known content length |
| [`09_async_video_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/09_async_video_upload.kt) | Start an asynchronous signed upload | Local file and destination |
| [`10_cancel_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/10_cancel_upload.kt) | Tie an upload handle to an owning component | Defined cancellation policy |
| [`11_worker_video_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/11_worker_video_upload.kt) | Run a blocking upload in a worker | Background thread |
| [`12_resumable_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/12_resumable_upload.kt) | Persist experimental multipart checkpoints | Multipart-capable custom gateway |
| [`13_adaptive_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/13_adaptive_upload.kt) | Select multipart settings from device/network conditions | Multipart-capable custom gateway |
| [`14_bulk_video_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/14_bulk_video_upload.kt) | Upload several sources as one async batch operation | Reopenable files and destination |
| [`15_metadata_jsonl_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/15_metadata_jsonl_upload.kt) | Append JSONL metadata through the metadata pipeline | Valid JSONL contract |
| [`17_collection_mutations.kt`](src/main/kotlin/com/vmodal/sdk/examples/17_collection_mutations.kt) | Add assets, update metadata, and preview deletion | Existing collection/assets |
| [`18_index_lifecycle.kt`](src/main/kotlin/com/vmodal/sdk/examples/18_index_lifecycle.kt) | Create, inspect, and preview deletion of an index | Existing collection data |
| [`19_image_access.kt`](src/main/kotlin/com/vmodal/sdk/examples/19_image_access.kt) | Resolve one or many image records and download bytes | Valid image coordinates |
| [`20_admin_and_r2.kt`](src/main/kotlin/com/vmodal/sdk/examples/20_admin_and_r2.kt) | Read operational stats and request an upload key | Privileged account |
| [`ContentUriUploadSource.kt`](src/main/kotlin/com/vmodal/sdk/examples/ContentUriUploadSource.kt) | Validate, retain access to, and reopen a `content://` URI | Persistable grant for durable work |
| [`ClassicAndroidIntegration.kt`](src/main/kotlin/com/vmodal/sdk/examples/ClassicAndroidIntegration.kt) | Own coroutine calls and callback handles outside an Activity or Fragment | Lifecycle-owned scope and explicit `close()` |
| [`VmodalUploadWorker.kt`](src/main/kotlin/com/vmodal/sdk/examples/VmodalUploadWorker.kt) | Validate account identity, reopen a URI, and bound reconciled retries | Account tag, URI grant, and app-owned client provider |
| [`CookbookWorkflow.kt`](src/main/kotlin/com/vmodal/sdk/examples/CookbookWorkflow.kt) | Keep upload, index, version selection, search, and bulk image mapping coupled | One immutable `CookbookScope` |

## Detailed walkthroughs

### 01 — Create a gateway client

[`01_create_gateway_client.kt`](src/main/kotlin/com/vmodal/sdk/examples/01_create_gateway_client.kt) demonstrates the
normal mobile-client bootstrap. `Client(baseUrl, token)` creates a gateway
client, `auth.me()` validates the bearer key and returns its mapped identity,
and `bootstrap.cfg.copy(...)` retains the same transport configuration while
adding the resolved `userId`, `tenantId`, and `email`.

Call `createGatewayClient()` once per authenticated session from a worker
thread. Retain the returned client instead of repeating `auth.me()` for every
operation. The function uses `requireNotNull(me.userId)`, so a successful
response without a mapped user fails immediately rather than creating a
partially identified client.

Prefer the mutable provider flow in example 03 when the key can rotate during
the lifetime of the retained client.

### 02 — Create a direct client

[`02_create_direct_client.kt`](src/main/kotlin/com/vmodal/sdk/examples/02_create_direct_client.kt) calls
`Client.unsafeDirect()` with an explicit base URL and user ID. It also shows
transport configuration through `timeoutMillis` and `maxRetries`.

This mode is not the production Android default. It bypasses the gateway's
bearer-derived identity model and is suitable only for an already trusted
private network. The downstream must authenticate and authorize the identity
independently. If the Android device connects over the public Internet, use
example 01 instead.

### 03 — Read identity and health

[`03_identity_and_health.kt`](src/main/kotlin/com/vmodal/sdk/examples/03_identity_and_health.kt) performs two blocking
GET operations. `auth.me()` verifies which user the current credential maps to;
`health()` reports service status and version. Use this pair as an early
integration diagnostic before investigating search coordinates or upload data.

Expected output has the following shape:

```text
user=<mapped-user-id> email=<mapped-email>
status=<service-status> version=<service-version>
```

An auth failure points to the runtime key or gateway configuration. A health
failure with successful identity usually points to service reachability or a
backend deployment issue.

### 03 — Rotate a runtime API key

[`03_rotate_api_key.kt`](src/main/kotlin/com/vmodal/sdk/examples/03_rotate_api_key.kt) separates responsibilities:

- `ApiKeyStore` represents app-owned persistence;
- `ApiKeyBackend` represents the authenticated backend refresh call;
- `MutableApiKeyProvider` supplies the latest already-loaded value to the SDK;
- `VmodalRuntime` keeps the client and provider together.

`createRotatingClient()` loads the cached key, authenticates it, and builds a
retained client. `refreshApiKey()` fetches a replacement, persists it, and then
rotates the provider. `clearApiKey()` removes the in-memory SDK value; the host
application must also delete its persisted copy.

Rotation is for a replacement credential representing the same user and
tenant. For logout, account switching, or tenant switching, cancel active work,
clear persisted state and the provider, then discard and recreate the client.
The provider's `current()` path must be synchronous and should never perform
network or disk I/O.

### 04 — Run a minimal text search

[`04_text_search.kt`](src/main/kotlin/com/vmodal/sdk/examples/04_text_search.kt) uses the convenient named-argument
overload of `searchVideo()`. It first resolves the exact authenticated video
group and its latest advertised LanceDB version, then searches its stream,
limits the response to 20 hits, and prints both the returned count and total
match count.

Before running it:

1. use example 06 to find a real video group;
2. replace `traffic-cameras` and `astream`;
3. choose a query that can plausibly match indexed content;
4. run the function from `Dispatchers.IO`.

`cntActual` describes records in the current response. `cntTotal` describes the
server-reported total for the query. Iterate `result.data` to inspect individual
records; stable typed fields are exposed directly and evolving server fields
remain available in raw maps.

### 05 — Search with filters and multiple signals

[`05_filtered_search.kt`](src/main/kotlin/com/vmodal/sdk/examples/05_filtered_search.kt) constructs a `SearchRequest`
when the query needs more than the convenience overload. The sample combines:

- text query `forklift`;
- metadata equality for `site=warehouse-a`;
- video-file mode and a specific group;
- OCR, ASR, and visual-image search signals;
- union combination behavior;
- an inclusive date window supplied as ISO-style date strings;
- a minimum text-embedding score;
- a result limit of 50;
- the latest LanceDB version advertised by the authenticated group list.

Remove filters first if the query unexpectedly returns no results, then add
them back one at a time. Metadata keys and values must match the indexed data,
and date filtering only helps when the relevant records contain the expected
date fields.

### 06 — List collection groups

[`06_list_groups.kt`](src/main/kotlin/com/vmodal/sdk/examples/06_list_groups.kt) lists groups in `vid_file` mode,
prints the server-reported total, and exposes typed `GroupItem` records. Use
`findGroup()` to resolve an exact name and `latestLancedbVersion` to select the
search index advertised for that authenticated collection.

Change the mode to `img_file` when discovering image-file groups. Treat the
returned group and stream names as server data, not display strings invented by
the app.

### 07 — Upload a small file through the legacy form endpoint

[`07_small_file_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/07_small_file_upload.kt) calls `uploadFile()` with
a local `File`, destination coordinates, description, and tags. This is the
legacy multipart-form endpoint and is intended only for small files.

For normal video ingestion, especially large videos or Android picker content,
prefer the signed upload functions in examples 08–11. The mutation is not
automatically replayed after an ambiguous network failure; reconcile backend
state before manually retrying.

### 08 — Convert an Android content URI

[`08_content_uri_source.kt`](src/main/kotlin/com/vmodal/sdk/examples/08_content_uri_source.kt) adapts a picker-provided
`content://` URI to the SDK's platform-neutral `UploadSource` contract.

The adapter:

1. reads a stable content length from `openAssetFileDescriptor()`;
2. asks the `ContentResolver` for the MIME type;
3. records the URI string as the source identity;
4. supplies an opener lambda that creates a fresh `InputStream` each time.

Reopenability matters because an upload or retry may open the source more than
once. Do not read the complete video into a `ByteArray`. Keep any required URI
permission for as long as the upload can run. If the provider reports an
unknown length, copy the content to an app-private file and use
`UploadSource.fromFile()`.

If the content at a URI can change without its URI or size changing, provide a
stable `versionTag` in your adapted implementation so persisted upload state is
not reused for different bytes.

### 09 — Start an asynchronous video upload

[`09_async_video_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/09_async_video_upload.kt) converts a local file to
an `UploadSource`, starts `videoUploadAsync()`, reports progress, and handles
success or failure. The return value is an `UploadHandle`; retain it while the
upload is active.

The default is a signed single upload for every file size. It streams bytes and
does not load the complete file into memory. `onSuccess` exposes the final
destination path. UI applications should translate callbacks into ViewModel
state and dispatch UI-only changes to the main thread.

### 10 — Cancel an owned upload

[`10_cancel_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/10_cancel_upload.kt) wraps the active handle in an
`UploadOwner`. `start()` replaces the stored handle with a new operation, while
`stop()` cancels active network work and clears the reference.

Choose cancellation semantics deliberately. Cancel when the user presses a
Cancel action or when the owning feature should terminate the work. Do not
automatically cancel merely because an Activity is recreated if the product
requires the upload to continue; move that operation to WorkManager instead.

Before allowing `start()` to be called repeatedly, decide whether the previous
handle should be cancelled explicitly. The compact example focuses only on
retaining and cancelling one operation.

### 11 — Upload from a worker

[`11_worker_video_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/11_worker_video_upload.kt) uses the blocking
`videoUpload()` counterpart. It is suitable inside `CoroutineWorker.doWork()`
with an IO context, a `Worker`, another background executor, or a JVM test. It
must never run on Android's main thread.

WorkManager owns scheduling, constraints, and reboot behavior. The SDK owns
request signing, streaming, upload progress, transport behavior, and upload
finalization. Convert an exception into the WorkManager result policy selected
by the host application; do not blindly retry an ambiguous mutation without
checking server state.

### 12 — Resume experimental multipart uploads

[`12_resumable_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/12_resumable_upload.kt) explicitly enables
multipart upload, enables resume behavior, and persists checkpoints below
`noBackupFilesDir`. Recreating the same source, destination, options, and
`FileUploadSessionStore` allows the SDK to reconcile stored checkpoint data
with server status after process death.

Multipart is experimental and is **not available on the standard production
gateway unless that gateway exposes the complete multipart route family**.
Enabling it against an unsupported gateway produces `FeatureDisabled`. Start
with the default single signed upload and adopt this sample only for a verified
custom deployment.

Checkpoint storage contains upload identifiers, object coordinates, sizes, and
part integrity data, not bearer tokens or presigned URLs. The directory is
app-private and excluded from routine backup in this example.

### 13 — Select adaptive multipart options

[`13_adaptive_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/13_adaptive_upload.kt) translates current Android
conditions into stable SDK enums, asks `AdaptiveUploadPolicy` which preset it
would select, and returns options that let the upload resolve the same policy.

The example declares fast Wi-Fi and high device memory. A real app must derive
those values from reviewed Android platform signals and handle unknown values
conservatively. The selected preset controls multipart part size, concurrency,
attempts, and timeout for the whole upload. It does not change midway through a
session.

Adaptive settings apply only to explicit multipart uploads, so the same custom
gateway requirement as example 12 applies.

### 14 — Upload several videos

[`14_bulk_video_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/14_bulk_video_upload.kt) maps local files to
reopenable sources and calls `videoUploadBulkAsync()`. The aggregate handle can
cancel the batch, aggregate progress reports a batch percentage, and the
success response reports the total uploaded count.

Validate the complete input list and destination before starting. Decide how
the host application will present partial failure or reconcile files after a
lost response. For very large queues, WorkManager jobs with explicit per-item
state may be easier to observe and resume than one UI-owned batch.

### 15 — Upload JSONL metadata

[`15_metadata_jsonl_upload.kt`](src/main/kotlin/com/vmodal/sdk/examples/15_metadata_jsonl_upload.kt) creates a
multipart file part with `filePart()` and appends JSONL metadata to an image
collection. `allowOverlap = false` asks the backend to reject overlapping input
according to its metadata contract.

Validate the JSONL schema, destination mode, group, and stream before upload.
`writeMode = "append"` is a data mutation; a network failure after the server
accepts the request can have an ambiguous outcome. Inspect backend state before
replaying the same file.

### 17 — Mutate a collection safely

[`17_collection_mutations.kt`](src/main/kotlin/com/vmodal/sdk/examples/17_collection_mutations.kt) demonstrates three
independent operations:

- add existing asset IDs to a collection;
- update one stored file's description and tags;
- request a dry-run preview of collection deletion.

The deletion call intentionally uses `dryRun = true` and `confirm = false`.
Inspect the preview before adding a separate, explicit confirmed deletion path.
Do not turn the demonstration into an automatic destructive action. Each
operation returns its raw response map so the compact function can return a
common list; production code can retain and model the typed responses directly.

### 18 — Manage index lifecycle

[`18_index_lifecycle.kt`](src/main/kotlin/com/vmodal/sdk/examples/18_index_lifecycle.kt) creates a new video index,
uses the returned `jobId` to inspect indexation status, and previews deletion of
an older version.

Index creation may be asynchronous; the first status response does not imply
that indexing has finished. A production app should poll using a bounded,
cancellable policy appropriate to the backend contract. Index deletion is
again a dry run in the example. Require explicit confirmation before executing
the destructive form.

### 19 — Resolve and download images

[`19_image_access.kt`](src/main/kotlin/com/vmodal/sdk/examples/19_image_access.kt) demonstrates both single and bulk
image URL resolution.

`downloadImage()` sends complete image coordinates, checks the `found` flag,
then downloads bytes from the returned presigned URL. The SDK's binary helper
is bounded in memory, so it is appropriate for small image responses rather
than unlimited streaming downloads.

`resolveImageBatch()` converts `ImageUrlRecord` to the map contract accepted by
`getUrlBulk()`. Real frame records may also require stream and 13-digit
timestamp coordinates. Preserve the input order or the server-provided input
index when mapping bulk responses back to search hits; the complete search app
shows that correlation logic.

### 20 — Read admin and R2 information

[`20_admin_and_r2.kt`](src/main/kotlin/com/vmodal/sdk/examples/20_admin_and_r2.kt) gathers usage, user, and cache
statistics, then requests presigned upload information for an object-storage
coordinate. These calls require an account with the corresponding permissions.

Presigned storage requests are separate from ordinary authenticated API calls.
Do not add the VModal bearer token or identity headers to the storage request.
Do not treat this administrative snapshot as a user-facing health check; use
example 03 for basic connectivity.

## Choosing an upload example

| Situation | Start with | Why |
|---|---|---|
| Small legacy form upload | Example 07 | Uses the simple legacy endpoint |
| Video selected from Android picker | Examples 08 + 09 | Streams a reopenable URI source asynchronously |
| User needs a Cancel button | Example 10 | Retains the SDK `UploadHandle` |
| Upload should outlive a screen | Example 11 | Blocking API fits worker ownership |
| Several UI-selected files | Example 14 | Provides aggregate async progress and cancellation |
| Process-death multipart resume | Example 12 | Persists and reconciles checkpoints |
| Multipart tuning by current conditions | Example 13 | Selects a stable adaptive preset |

Use examples 12 and 13 only after verifying that a custom gateway supports the
complete multipart route family. Multipart is never selected automatically by
file size.

## Error handling

All SDK failures derive from `SdkError`. The host app can distinguish:

| Error | Typical meaning | Application response |
|---|---|---|
| `AuthError` | Missing, expired, revoked, or rejected credential | Refresh through the authenticated backend or sign out |
| `ValidationFailed` | Invalid local argument or upload contract | Correct input before sending another request |
| `ApiError` | Backend returned an HTTP/API failure | Show a safe message and use status/context for policy |
| `FeatureDisabled` | Optional server capability is unavailable | Fall back or disable the feature, especially multipart |
| `TransportError` | Network or transport failure | Check connectivity and mutation ambiguity before retrying |
| `ResponseTooLarge` | Response exceeded an SDK memory bound | Narrow the request or use an appropriate streaming path |
| `MalformedResponse` | Response violated the expected JSON contract | Record safe diagnostics and investigate API compatibility |

The SDK retries recognized transient failures only for `GET` and `HEAD`.
Searches, presign calls, uploads, and other mutations are not blindly replayed.
If a mutation's response is lost, reconcile server state before retrying.

## Troubleshooting

### Android reports a main-thread network exception

The copied function contains a blocking call. Move it to `Dispatchers.IO`, a
worker, or another executor. Async upload callbacks being off-main does not make
other SDK resource calls asynchronous.

### Authentication succeeds once but later requests return 401

The runtime key may have expired or been revoked. Fetch a replacement through
the authenticated application backend and rotate the same-user provider. On an
account or tenant change, clear and recreate the complete client session.

### Search returns no records

List groups first, verify mode/group/stream coordinates, remove filters, and
use a broad query. Then add search sources, dates, metadata, and score thresholds
one at a time.

### Search returns records but image resolution returns nothing

Confirm that each record contains the filename, group, stream, modality, and
timestamp coordinates expected by the image service. Review the mapping in
[`../02_search/SearchViewModel.kt`](../02_search/app/src/main/kotlin/com/vmodal/sdk/examples/search/SearchViewModel.kt).

### A content URI cannot report its size

Copy it to an app-private file and use `UploadSource.fromFile()`. Retain URI
read permission while copying. Do not substitute an estimated length.

### Upload progress does not update the UI

The callback is on a worker thread. Send state to the main dispatcher or update
a thread-safe ViewModel flow using the application's normal coroutine policy.

### Multipart fails with `FeatureDisabled`

The configured gateway does not expose the experimental route family. Remove
`multipart = true` and use the default signed single upload.

### A mutation timed out

Do not immediately repeat it. The server may have completed the operation even
though the response was lost. Query the relevant collection, index, upload, or
metadata state and decide from the reconciled result.

## Integration checklist

Before treating a copied example as production-ready, verify that:

- the call runs off the main thread;
- no credential is compiled into the APK or written to logs;
- gateway mode is used unless a trusted direct deployment is intentional;
- collection, stream, mode, modality, filename, and timestamp coordinates come
  from real backend data;
- UI state updates return to the appropriate dispatcher;
- upload sources are reopenable and report an exact content length;
- the owning component retains and cancels active handles according to product
  behavior;
- WorkManager owns uploads that must survive beyond a screen;
- ambiguous mutations are reconciled before replay;
- destructive collection and index actions require an explicit preview and
  confirmation path;
- multipart is enabled only for a verified capable gateway;
- logout clears app persistence, clears the provider, cancels work, and drops
  the retained client.

## Further reading

- [VModal Android SDK overview](../../README.md)
- [Complete Compose upload → index → search example](../02_search/README.md)
- [Upload and WorkManager guide](../../docs/sdk_doc.md)
- [Mobile credential lifecycle](../../docs/manage_api_key.md)
- [Search app design guide](../../docs/search_app.md)
- [SDK API quick reference](../../DOC_REF.md)

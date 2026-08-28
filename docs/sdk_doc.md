# VModal Android scoped content guide

For new content integrations, configure one developer project and create an
immutable collection/stream scope:

```kotlin
import com.vmodal.sdk.VModal

val content = VModal.configure(
    projectId = "food_app",
    apiKey = apiKey,
).scope(
    collectionName = "user_123",
    streamName = "uploads",
)

val upload = content.upload(source)
val results = content.search("birthday dinner")
```

The SDK validates and maps the public project, collection, and stream names
before transport. A scope can be shared by ViewModels and workers without
mutable selection state. Existing `Client` resources remain available for
authentication, administration, images, R2, and advanced compatibility calls.

This guide starts with a working Android upload and then adds reliability and
performance features one at a time. Complete the root [quick start](../README.md)
first so you already have an authenticated `Client` named `sdk`.

For exact Kotlin signatures and linked types, use the generated
[Kotlin SDK reference](../docs_sdk/index.html). It is generated from public KDoc
and intentionally excludes raw service hosts, endpoint paths, route tables, and
implementation source.

For runtime integration across Compose/ViewModel, classic Views, `content://`,
WorkManager, upload → index → search coupling, typed UI states, and credential
cleanup, start with the [Android integration cookbook](android_integration_cookbook.md).
The consuming application owns UI state, navigation, accessibility, theming,
and its design system.

## SDK API surface List

Choose the smallest public surface that matches the integration:

| SDK surface | Entry point | Use it for |
|---|---|---|
| Scoped content facade | `VModal.configure(...).scope(...)` | New upload, metadata, search, asset, index, and collection flows where project, collection, and stream must stay coupled |
| Coroutine resources | `Client.coroutines()` | Authentication, administration, images, R2, and lower-level content operations from a caller-owned coroutine scope |
| Blocking resources | `Client(...)`, `Client.fromEnv(...)` | Existing Java, worker-thread, command-line, and compatibility integrations |
| Callback uploads | `videoUploadAsync(...)`, `videoUploadBulkAsync(...)` | Existing callback code that needs an `UploadHandle` for cancellation |
| Pre-upload transcode | `VideoTranscoder`, `VideoUploadOptions.transcoder` | Reducing resolution (for example to 360px) on device before upload; default is no transcoding |
| Extension and test contracts | `VmodalTransport`, `SignedUploadTransport`, `VmodalHttp`, `VmodalFilePart`, `VmodalJson` | Injected transports, deterministic tests, and custom trusted integrations |

The scoped content facade is the preferred surface for new Android content
features:

- `VModal.configure(...)` creates an immutable `VModalProject`.
- `VModalProject.scope(collectionName, streamName)` creates an immutable
  `VModalScope`.
- `VModalProject.listCollections(...)` lists decoded collections belonging to
  that project.
- `VModalScope` provides `upload`, `uploadEvents`, `uploadMetadata`, `search`,
  `addAssets`, `updateAsset`, `createIndex`, `listIndexJobs`, `indexStatus`,
  `deleteIndex`, and `deleteCollection`.

The coroutine and blocking client surfaces expose the same primary resource
families:

| Resource | Coroutine surface | Blocking surface | Responsibility |
|---|---|---|---|
| Authentication | `CoroutineClient.auth` | `Client.auth` | Identity, authentication checks, and health |
| Collections | `CoroutineClient.collections` | `Client.collections` | Collection discovery, mutation, metadata, and uploads |
| Search | `CoroutineClient.searches` | `Client.searches` | Typed multimodal search |
| Indexes | `CoroutineClient.indexes` | `Client.indexes` | Index creation, status, listing, and deletion |
| Images | `CoroutineClient.images` | `Client.images` | Signed image URLs and bounded image retrieval |
| Administration | `CoroutineClient.admin` | `Client.admin` | Usage and administrative reporting |
| Object storage | `CoroutineClient.r2` | `Client.r2` | Low-level signed object-storage operations |

`Client.gdrive` and `Client.sql` remain blocking compatibility placeholders.
They fail locally with `FeatureDisabled` and are not active service surfaces.
All surfaces share the typed request/response models and the `SdkError`
hierarchy. The coroutine facade owns no lifecycle scope; the application calls
it from `viewModelScope`, `lifecycleScope`, an application-owned scope, or
`CoroutineWorker`.

## Runtime security contract

Ordinary API requests automatically retry only `GET` and `HEAD`, for recognized
transport failures or HTTP `500`, `502`, `503`, and `504`, up to
`1 + maxRetries` total attempts. `POST`, `PUT`, `PATCH`, and `DELETE` are sent
once because a lost response is an ambiguous mutation outcome. This includes
POST-based searches and presign calls. Applications must reconcile state before
choosing to replay them. Signed multipart part recovery remains separate: it
uses part status and ETag/MD5 reconciliation before retransmission.

Responses are bounded in memory even if `Content-Length` is missing, false, or
smaller than the delivered body. Defaults are 8 MiB for JSON/text success,
1 MiB for errors, and 64 MiB for binary success. `requestBytes()` stores only
the byte payload and leaves the text body empty. `ResponseTooLarge` reports the
limit and observed/declared count without including response content. These are
bounded in-memory APIs; they are not unlimited download streams.

JSON uses strict Moshi parsing with one complete top-level value. Comments,
single quotes, trailing values, malformed escapes, non-finite numbers, and
excessive nesting fail with a redacted `MalformedResponse`. A malformed nonempty
response is never silently converted to an empty object. Upload checkpoints are
also limited to 1 MiB before decoding and parsing.

Multipart field names, filenames, and content types reject blank, over-limit,
or control-character values. Quotes and backslashes in accepted Unicode names
are escaped before header encoding; file bytes and form values are unchanged.

Gateway mode is the default and sends no caller-provided identity headers or
image/body identity overrides; the bearer credential is the identity source.
`Client.unsafeDirect(...)` is only for an already trusted private network where the
downstream independently authenticates and authorizes identity headers. Do not
expose direct mode to an untrusted public network. Production mobile clients
must use the authenticated gateway with user-scoped, revocable, short-lived
credentials supplied by their application backend.

## Before uploading

Keep these three rules in mind:

1. Prefer `VModal.configure(...).scope(...)` for scoped content operations and
   `Client.coroutines()` for lower-level operations.
2. Stream Android `content://` URIs; do not read a complete video into memory.
3. Start with default upload settings. Add persistent resume or adaptive tuning
   only after the basic upload works.

The SDK's core is plain Kotlin/JVM and does not import Android framework
classes. Your app provides Android-specific values such as a `ContentResolver`,
network type, and available device memory.

## Step 1: convert a content URI to an upload source

After the user selects a video, Android normally gives the app a `Uri`. Build a
reopenable `UploadSource` from it:

```kotlin
import android.content.Context
import android.net.Uri
import com.vmodal.sdk.UploadSource

fun videoSource(context: Context, uri: Uri, fileName: String): UploadSource {
    val resolver = context.contentResolver
    val size = resolver.openAssetFileDescriptor(uri, "r")!!.use { descriptor ->
        require(descriptor.length >= 0) { "The selected video must report its size" }
        descriptor.length
    }

    return UploadSource(
        fileName = fileName,
        contentLength = size,
        contentType = resolver.getType(uri) ?: "video/mp4",
        sourceId = uri.toString(),
    ) {
        resolver.openInputStream(uri) ?: error("Unable to open $uri")
    }
}
```

Why the source must be reopenable: retries and multipart uploads may open the
same URI more than once. The SDK streams each range and does not keep the whole
video in memory.

If the content behind a URI can change without changing its URI or size, also
set `versionTag` to a provider generation or last-modified value.

## Step 2: collect upload progress

```kotlin
import com.vmodal.sdk.VideoUploadEvent

val source = videoSource(context, videoUri, "video.mp4")

sdk.coroutines().collections.videoUploadEvents(
    source = source,
    collectionName = "my_collection",
    subCollectionName = "astream",
).collect { event ->
    when (event) {
        is VideoUploadEvent.Progress -> println("Uploaded ${event.progress.percent}%")
        is VideoUploadEvent.Completed -> println("Uploaded to ${event.response.destPath}")
    }
}
```

This cold Flow starts one upload each time it is collected. Collect it once for
one operation. If several screens need the same state, collect once in an
application-owned scope and expose `stateIn`, `shareIn`, or a repository
`StateFlow`. The SDK does not own an application or UI scope.

Existing callback code can continue to use `videoUploadAsync(...)` and retain
its returned `UploadHandle`; the callback API remains supported for
operation-by-operation migration. Every file size uses one signed upload by
default. Multipart is never selected automatically by file size.

The built-in high-level upload engine has separate bounded orchestration and
data lanes. If its operation queue is full, callback APIs invoke `onFailure`
with `ValidationFailed` before returning the stopped (but not user-canceled)
handle; suspend and Flow surfaces receive the same typed failure. This bound
does not apply to direct calls to an injected `SignedUploadTransport`.

The `/api/external/v1/collections/external_upload_multipart/*` route family is
not available on the production gateway. `VideoUploadOptions(multipart = true)`
is an explicit experimental opt-in for a custom gateway with the complete
route family. A missing route produces a clear `FeatureDisabled` error.
Only one active multipart operation may own an exact destination/source/part
contract. A duplicate fails immediately with `ValidationFailed` before reading
the source, checkpoint, or network. Different session keys remain independent.

Collect from caller-owned `viewModelScope`, `lifecycleScope`, or a worker. The
SDK never hard-codes `Dispatchers.Main`; the application owns UI state and
lifecycle-aware collection.

## Step 3: support cancellation

Cancel the caller-owned collection job:

```kotlin
uploadJob.cancel()
```

Collector cancellation cancels the underlying upload handle, stops active
calls, and prevents new retries, signing, completion, and finalization. Do not
catch and wrap `CancellationException`. Callback integrations can still call
`UploadHandle.cancel()` directly.

Do not cancel automatically when leaving a screen if the intended product
behavior is a background upload. Use WorkManager for that case.

## Step 4: move long uploads to WorkManager

Use `CoroutineWorker` and collect the same Flow. Worker cancellation propagates
through collection to the active upload:

```kotlin
override suspend fun doWork(): Result = try {
    sdk.coroutines().collections.videoUploadEvents(
        source,
        collectionName = "my_collection",
        subCollectionName = "astream",
    ).collect { event ->
        if (event is VideoUploadEvent.Progress) {
            setProgress(workDataOf("progress" to event.progress.percent))
        }
    }
    Result.success()
} catch (error: CancellationException) {
    throw error
}
```

Never convert cancellation into `Result.retry()`. Apply a bounded retry only to
appropriate transient transport failures or HTTP `408`, `429`, `500`, `502`,
`503`, and `504`; reconcile an ambiguous mutation before replay. The complete
compile-checked pattern is
[`VmodalUploadWorker.kt`](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/VmodalUploadWorker.kt).

WorkManager owns background and reboot scheduling; the SDK owns signing,
streaming, cancellation, multipart completion, and checkpoint reconciliation.
The blocking `videoUpload()` remains available for existing worker and Java
integrations but must never run on the Android main thread.

## Step 5: resume experimental multipart after process death

The default in-memory checkpoint store can resume transient failures while the
app process remains alive. For process-death recovery, keep checkpoints in an
app-private directory that is not routinely cleared:

```kotlin
import com.vmodal.sdk.FileUploadSessionStore
import com.vmodal.sdk.VideoUploadOptions
import java.io.File

val options = VideoUploadOptions(
    multipart = true,
    sessionStore = FileUploadSessionStore(
        File(context.noBackupFilesDir, "vmodal-upload-checkpoints")
    ),
)

sdk.collections.videoUploadAsync(
    source = source,
    collectionName = "my_collection",
    subCollectionName = "astream",
    options = options,
    onSuccess = { println(it.destPath) },
    onFailure = { it.printStackTrace() },
)
```

Use this only after confirming the selected gateway exposes the complete
multipart capability. After a restart, construct the same destination, source identity, and
`FileUploadSessionStore`. The SDK compares the checkpoint with authoritative
server status and uploads only missing or invalid parts.

Checkpoint files contain multipart IDs, object keys, sizes, and verified part
MD5 values. They do not contain bearer tokens or presigned URLs. A checkpoint
is deleted only after finalization succeeds. Set `resume = false` only when the
app should abort a stored multipart session and start over.

## Step 6: optionally adapt multipart settings

Adaptive settings apply only to explicit multipart uploads. If a capable custom
gateway needs multipart tuning, translate Android observations into the SDK's
stable enums:

```kotlin
import com.vmodal.sdk.UploadConditions
import com.vmodal.sdk.UploadDeviceMemory
import com.vmodal.sdk.UploadNetworkSpeed
import com.vmodal.sdk.UploadNetworkType
import com.vmodal.sdk.VideoUploadOptions

val conditions = UploadConditions(
    networkType = UploadNetworkType.WIFI,
    networkSpeed = UploadNetworkSpeed.FAST,
    deviceMemory = UploadDeviceMemory.HIGH,
)
val options = VideoUploadOptions(multipart = true, adaptiveConditions = conditions)
val selected = options.resolvedFor(source.contentLength)
```

The selected preset controls part size, concurrency, attempts, and timeout for
the whole upload. The choice is made once so a multipart session cannot change
its part contract in the middle of a run.

Conservative settings are selected for low memory or unknown networks.
Cellular uploads use at most two concurrent parts. Fast Wi-Fi uses its fastest
preset only on a high-memory device. The policy also increases part size when
needed to stay below the upstream 10,000-part limit.

## Step 7: reduce resolution before upload (optional transcode)

To cut upload bytes, a clip can be transcoded to a lower resolution (longer side
360px) before it is sent. The core SDK is a pure Kotlin/JVM library with no
Android-framework dependency, so it ships only the small `VideoTranscoder`
interface and performs **no transcoding by default**. The app plugs in a device
transcoder — for example one built on `androidx.media3.transformer` — through
`VideoUploadOptions.transcoder`:

```kotlin
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.vmodal.sdk.TranscodeResult
import com.vmodal.sdk.VideoTranscoder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** App-side transcoder: scales the longer side to 360px and writes an mp4 to the app cache. */
class Media3TransformerTranscoder(private val context: Context) : VideoTranscoder {
    override fun reduce(input: File): TranscodeResult {
        val out = File(context.cacheDir, "vmodal_reduced_${input.name}")
        if (out.isFile && out.length() > 0) return TranscodeResult(out, reused = true)

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(input.toURI().toString()))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(360))))
            .build()
        val error = AtomicReference<Exception>()
        val latch = CountDownLatch(1)
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) = latch.countDown()
                override fun onError(c: Composition, r: ExportResult, e: androidx.media3.transformer.ExportException) {
                    error.set(e); latch.countDown()
                }
            })
            .build()
        // Transformer requires the main thread to start; run this from a main-dispatched coroutine.
        transformer.start(edited, out.absolutePath)
        latch.await()
        error.get()?.let { throw it }
        return TranscodeResult(out, reused = false)
    }
}

// Usage — default remains no transcoding; opt in per upload:
val options = VideoUploadOptions().apply { transcoder = Media3TransformerTranscoder(context) }
val response = sdk.collections.videoUpload(UploadSource.fromFile(file), "collection", "stream", options = options)
// response.reduceSize == true, response.sourceSizeBytes == original bytes,
// response.sizeBytes == uploaded (reduced) bytes, and the produced temp is deleted.
```

Transcoding requires a **file-backed** source (`UploadSource.fromFile`); a
stream-only source with a non-passthrough transcoder raises `ValidationFailed`.
Only a file the transcoder *produces* (a different path than the input) is
uploaded and then deleted — the app's original file is never removed. The
Gradle dependency is `androidx.media3:media3-transformer` (Apache-2.0); no
ffmpeg binary is needed or supported on device.

## What signed multipart upload handles automatically

- Streaming with a 256 KiB buffer per active part
- A streaming MD5 digest per active part
- Up to four concurrent parts by default
- Retry of transport failures and HTTP `408`, `429`, `500`, `502`, `503`, and
  `504`
- Refreshing a part's presigned URL after HTTP `403`
- Checking server status before retransmitting a part when the response may
  have been lost
- Excluding VModal identity and authorization headers from presigned R2 PUTs
- Exact monotonic aggregate progress across part retries
- Cooperative sibling-call cleanup before a high-level terminal failure

Other 4xx responses and integrity failures stop immediately so the app can show
the real error instead of retrying indefinitely.

`UploadSource.fromFile(file)` seeks directly to each part offset. A custom
source can provide `rangeOpener = { offset -> ... }` if its provider supports
seeking. Otherwise, the SDK uses bounded buffered skipping.

Low-level multipart-form file parts also require truthful declared lengths.
Both built-in transports advertise one exact total and reject early EOF or
extra source bytes instead of silently emitting a different request body.

## Troubleshooting uploads

### The content URI reports no size

Multipart upload needs a stable byte length. Copy the selected content into an
app-private file first, then use `UploadSource.fromFile(file)`.

### Progress updates do not change the UI

Collect in `viewModelScope` and expose immutable UI state. Existing callback
integrations must post main-thread-only state through their app-owned scope.

### An upload restarts after the app process dies

The default checkpoint store is memory-only. Configure
`FileUploadSessionStore` and recreate the same upload contract in WorkManager.

### Upload tuning causes validation errors

Return to `VideoUploadOptions()` single-upload defaults first. For an explicit
multipart upload, part size must be at least 5 MiB, concurrency must be between
1 and 16, attempts between 1 and 10, and a
multipart upload cannot exceed 10,000 parts.

## Verify the SDK

From the repository root:

```bash
cd uinterface/sdk_android
bash test.sh ci
bash test.sh all
```

The `ci` command publishes the tested `com.vmodal:vmodal-sdk-android` bytes to
a temporary isolated Maven repository, verifies their SHA-256 manifest, compiles
the standalone Kotlin/JVM consumer with a fresh Gradle home, and builds both
Android examples from that exact coordinate. Pass an absolute new or empty
repository path to preserve the artifact: `bash test.sh ci /tmp/vmodal-maven`.
All CI Gradle tasks use strict dependency verification and no credential.

The normal `test` task includes the executable model/route regression suites
and the deterministic transport integration suite. The transport suite binds
only to ephemeral loopback ports and normally completes in a few seconds. It
uses the real URL-connection and signed-upload transports to verify bearer and
trusted-identity boundaries, credential rotation, safe-method retries,
mutation non-retry behavior, terminal redirects, timeouts, typed failures,
exact JSON/form/multipart/binary encoding, response bounds, and presigned-host
credential isolation. It does not read a live API key or contact an external
host.

The pull-request gate is `.github/workflows/sdk_android_ci.yml`; it is offline,
read-only, and needs no emulator or credential. The separate release workflow
`.github/workflows/sdk_android_test_release.yml` runs a causally
connected signed upload, index creation, bounded status poll, fixture search,
index deletion, collection deletion, and absence checks, plus image and bulk
smoke coverage. Multipart protocol behavior is verified offline until its
production routes exist.

## Opt-in network diagnostics

Structured network diagnostics are disabled by default. Applications can attach a `DiagnosticSink`
with `SdkConfig.withDiagnostics(...)` to observe sanitized request-start, response, and failure
events across gateway, users API, and signed-upload attempts. The SDK removes raw URLs, headers,
bodies, credentials, signatures, exception messages, and uploaded bytes before sink delivery.

See [Redacted network diagnostics](network_diagnostics.md) for event ordering, retry correlation,
bounded text previews, the Kotlin/JVM-safe Logcat adapter, and log-retention responsibilities.

For individual methods and response types, continue to the
[API quick reference](../DOC_REF.md). For ViewModel ownership, cold-Flow
sharing, migration, and cancellation guarantees, read
[Coroutines and upload Flow](coroutines.md) and the
[Android integration cookbook](android_integration_cookbook.md).

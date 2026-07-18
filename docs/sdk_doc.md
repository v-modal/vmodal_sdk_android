# VModal Android upload guide

This guide starts with a working Android upload and then adds reliability and
performance features one at a time. Complete the root [quick start](../README.md)
first so you already have an authenticated `Client` named `sdk`.

For exact Kotlin signatures and linked types, use the generated
[Kotlin SDK reference](../docs_sdk/index.html). It is generated from public KDoc
and intentionally excludes raw service hosts, endpoint paths, route tables, and
implementation source.

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

1. Use a worker thread for blocking SDK calls.
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

## Step 2: start an asynchronous upload

```kotlin
import com.vmodal.sdk.videoUploadAsync

val source = videoSource(context, videoUri, "video.mp4")

val handle = sdk.collections.videoUploadAsync(
    source = source,
    collectionName = "my_collection",
    subCollectionName = "astream",
    onProgress = { progress ->
        println("Uploaded ${progress.percent}%")
    },
    onSuccess = { result ->
        println("Uploaded to ${result.destPath}")
    },
    onFailure = { error ->
        error.printStackTrace()
    },
)
```

This is enough for a first upload. Every file size uses one signed upload by
default. Multipart is never selected automatically by file size.

The `/api/external/v1/collections/external_upload_multipart/*` route family is
not available on the production gateway. `VideoUploadOptions(multipart = true)`
is an explicit experimental opt-in for a custom gateway with the complete
route family. A missing route produces a clear `FeatureDisabled` error.

The callbacks run off the Android main thread. Switch to `Dispatchers.Main`
before changing views or other main-thread-only state.

## Step 3: support cancellation

Keep the returned `UploadHandle` while the upload is active:

```kotlin
handle.cancel()
```

Cancellation stops active calls and prevents new retries, signing, completion,
and finalization. Call it when the user presses Cancel or at a lifecycle
boundary where the upload should no longer continue.

Do not cancel automatically when leaving a screen if the intended product
behavior is a background upload. Use WorkManager for that case.

## Step 4: move long uploads to WorkManager

`videoUpload()` is the blocking counterpart intended for workers and tests:

```kotlin
import com.vmodal.sdk.videoUpload

val result = sdk.collections.videoUpload(
    source = source,
    collectionName = "my_collection",
    subCollectionName = "astream",
    onProgress = { println("Uploaded ${it.percent}%") },
)
```

Never call the blocking form on the Android main thread. WorkManager owns
background and reboot scheduling; the SDK owns signing, streaming, retries,
multipart completion, and checkpoint reconciliation.

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

Other 4xx responses and integrity failures stop immediately so the app can show
the real error instead of retrying indefinitely.

`UploadSource.fromFile(file)` seeks directly to each part offset. A custom
source can provide `rangeOpener = { offset -> ... }` if its provider supports
seeking. Otherwise, the SDK uses bounded buffered skipping.

## Troubleshooting uploads

### The content URI reports no size

Multipart upload needs a stable byte length. Copy the selected content into an
app-private file first, then use `UploadSource.fromFile(file)`.

### Progress updates do not change the UI

Upload callbacks run on a worker thread. Post UI state to the main dispatcher.

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
gradle --no-daemon clean build publishToMavenLocal
cd examples/02_search
./gradlew --no-daemon :app:assembleDebug \
  -PvmodalUseMavenLocal=true -PvmodalSdkVersion=1.0.0
```

The local suite is offline and needs no emulator or credential. The live CI
gate is `.github/workflows/sdk_android_test_release.yml`; it runs a causally
connected signed upload, index creation, bounded status poll, fixture search,
index deletion, collection deletion, and absence checks, plus image and bulk
smoke coverage. Multipart protocol behavior is verified offline until its
production routes exist.

For individual methods and response types, continue to the
[API quick reference](../DOC_REF.md).

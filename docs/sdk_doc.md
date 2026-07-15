# VModal Android upload guide

This guide starts with a working Android upload and then adds reliability and
performance features one at a time. Complete the root [quick start](../README.md)
first so you already have an authenticated `Client` named `sdk`.

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

This is enough for a first upload. Files smaller than 100 MiB use one signed
upload by default; files at or above 100 MiB select multipart upload.

**TODO: production does not currently expose the
`/api/external/v1/collections/external_upload_multipart/*` route family. Set
`VideoUploadOptions(multipart = false)` in production until those routes are
available. Multipart behavior remains covered by the offline regression suite.**

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

## Step 5: resume after process death

The default in-memory checkpoint store can resume transient failures while the
app process remains alive. For process-death recovery, keep checkpoints in an
app-private directory that is not routinely cleared:

```kotlin
import com.vmodal.sdk.FileUploadSessionStore
import com.vmodal.sdk.VideoUploadOptions
import java.io.File

val options = VideoUploadOptions(
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

After a restart, construct the same destination, source identity, and
`FileUploadSessionStore`. The SDK compares the checkpoint with authoritative
server status and uploads only missing or invalid parts.

Checkpoint files contain multipart IDs, object keys, sizes, and verified part
MD5 values. They do not contain bearer tokens or presigned URLs. A checkpoint
is deleted only after finalization succeeds. Set `resume = false` only when the
app should abort a stored multipart session and start over.

## Step 6: optionally adapt multipart settings

Start with defaults. If large uploads must adapt to current device conditions,
translate Android observations into the SDK's stable enums:

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
val options = VideoUploadOptions(adaptiveConditions = conditions)
val selected = options.resolvedFor(source.contentLength)
```

The selected preset controls part size, concurrency, attempts, and timeout for
the whole upload. The choice is made once so a multipart session cannot change
its part contract in the middle of a run.

Conservative settings are selected for low memory or unknown networks.
Cellular uploads use at most two concurrent parts. Fast Wi-Fi uses its fastest
preset only on a high-memory device. The policy also increases part size when
needed to stay below the upstream 10,000-part limit.

## What the SDK handles automatically

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

Return to `VideoUploadOptions()` defaults first. Part size must be at least
5 MiB, concurrency must be between 1 and 16, attempts between 1 and 10, and a
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
gate is `.github/workflows/sdk_android_test_release.yml`; it checks identity,
health, search, CRUD, images, small upload, single signed video upload, and bulk
video upload. This matches the default `sdk_python` live coverage. Multipart
protocol behavior is verified offline until its production routes exist.

For individual methods and response types, continue to the
[API quick reference](../DOC_REF.md).

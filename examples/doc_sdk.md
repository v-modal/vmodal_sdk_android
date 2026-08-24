# VModal Android SDK — examples guide

Compile-checked examples for the `com.vmodal:vmodal-sdk-android` SDK. Each folder is a
self-contained Gradle project that consumes the published SDK coordinate.

| Example | Shows |
|---|---|
| [`01_starter`](01_starter/) | Minimal client setup, a signed upload, and a `WorkManager` worker |
| [`02_search`](02_search/) | Search over a collection and stream |
| [`03_fullapp`](03_fullapp/) | End-to-end app flow (upload, index, grid, search) |
| [`04_user`](04_user/) | Organizing content by project, collection, and stream |

The canonical, prose walkthrough of every upload step lives in
[`../docs/sdk_doc.md`](../docs/sdk_doc.md). This file is the example-oriented quick reference.

## Configure a client

```kotlin
import com.vmodal.sdk.Client
import com.vmodal.sdk.SdkConfig

val sdk = Client(SdkConfig.fromEnv())        // or Client(SdkConfig(baseUrl, userId, ...))
```

Obtain the API key at runtime; never embed it in application source or resources.

## Upload a video

```kotlin
import com.vmodal.sdk.UploadSource

val response = sdk.collections.videoUpload(
    UploadSource.fromFile(file), "collection", "stream",
)
println(response.destPath)
```

`UploadSource` accepts a file, a byte array, or a reopenable `content://` adapter. For long
uploads use `videoUploadAsync(...)` (callbacks + `UploadHandle`) or the cold-Flow
`videoUploadEvents(...)`; see [`../docs/coroutines.md`](../docs/coroutines.md).

## Reduce resolution before upload (optional transcode)

Transcoding to a lower resolution (longer side 360px) before upload cuts upload bytes. The SDK
core is a pure Kotlin/JVM library with **no Android-framework dependency**, so it ships only the
`VideoTranscoder` interface and does **no transcoding by default**. The app injects its own device
transcoder — for example one built on `androidx.media3.transformer` — through
`VideoUploadOptions.transcoder`.

Add the Media3 dependency in the **app** module (not the SDK core):

```kotlin
// app/build.gradle.kts
implementation("androidx.media3:media3-transformer:<version>")
implementation("androidx.media3:media3-effect:<version>")
implementation("androidx.media3:media3-common:<version>")
```

Provide a transcoder that scales the longer side to 360px and writes to the app cache:

```kotlin
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.vmodal.sdk.TranscodeResult
import com.vmodal.sdk.VideoTranscoder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

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
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    error.set(e); latch.countDown()
                }
            })
            .build()
        // Transformer.start() must run on the main thread; call reduce() from a main-thread coroutine.
        transformer.start(edited, out.absolutePath)
        latch.await()
        error.get()?.let { throw it }
        return TranscodeResult(out, reused = false)
    }
}
```

Inject it per upload — the default stays "no transcoding":

```kotlin
import com.vmodal.sdk.UploadSource
import com.vmodal.sdk.VideoUploadOptions

val options = VideoUploadOptions().apply { transcoder = Media3TransformerTranscoder(context) }
val response = sdk.collections.videoUpload(
    UploadSource.fromFile(file), "collection", "stream", options = options,
)
// response.reduceSize == true
// response.sourceSizeBytes == original file bytes
// response.sizeBytes       == uploaded (reduced) bytes
// the produced temp file is deleted after a successful upload; the original is untouched
```

Notes:
- Transcoding requires a **file-backed** source (`UploadSource.fromFile`). A stream-only source
  with a non-passthrough transcoder raises `ValidationFailed`.
- Only a file the transcoder **produces** (a different path than the input) is uploaded and then
  deleted. The app's original file is never removed.
- Do **not** try to install or bundle an `ffmpeg` CLI on Android — there is no system `ffmpeg`
  and app sandboxing blocks executing a bundled binary. Use Media3 Transformer or `MediaCodec`.

## Compile the examples

From `uinterface/sdk_android`:

```bash
examples/02_search/gradlew -p examples/04_user --no-daemon \
  --dependency-verification off compileUserExamples
```

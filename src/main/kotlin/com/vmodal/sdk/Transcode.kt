package com.vmodal.sdk

import java.io.File

/**
 * Result of a pre-upload transcode pass.
 *
 * @property output reduced media file, or the original when passing through
 * @property reused whether [output] was served from a prior cached reduction
 */
data class TranscodeResult(val output: File, val reused: Boolean = false)

/**
 * Pluggable pre-upload video transcoder.
 *
 * The core SDK is a pure-JVM library and intentionally has no Android framework dependency, so the
 * real device transcoder (for example `androidx.media3.transformer`) is supplied by the app rather
 * than bundled here. The default [PassthroughTranscoder] performs no transcoding. An app that wants
 * to shrink a clip (for example to a 360px longer side) injects its own implementation through
 * [VideoUploadOptions.transcoder].
 *
 * ## How to inject a transcoder
 *
 * Transcoding is opt-in and per-upload. The default is no transcoding, so existing callers are
 * unaffected. To reduce resolution before upload, set [VideoUploadOptions.transcoder] to your
 * implementation and pass a **file-backed** source ([UploadSource.fromFile]); a stream-only source
 * with a non-passthrough transcoder raises [ValidationFailed].
 *
 * ```kotlin
 * // 1. Add the Media3 dependency in the APP module (not the SDK core):
 * //    implementation("androidx.media3:media3-transformer:<version>")
 * //    implementation("androidx.media3:media3-effect:<version>")
 * //    implementation("androidx.media3:media3-common:<version>")
 *
 * // 2. Provide a device transcoder that scales the longer side to 360px and writes to the cache:
 * import android.content.Context
 * import androidx.media3.common.MediaItem
 * import androidx.media3.effect.Presentation
 * import androidx.media3.transformer.Composition
 * import androidx.media3.transformer.EditedMediaItem
 * import androidx.media3.transformer.Effects
 * import androidx.media3.transformer.ExportException
 * import androidx.media3.transformer.ExportResult
 * import androidx.media3.transformer.Transformer
 * import com.vmodal.sdk.TranscodeResult
 * import com.vmodal.sdk.VideoTranscoder
 * import java.io.File
 * import java.util.concurrent.CountDownLatch
 * import java.util.concurrent.atomic.AtomicReference
 *
 * class Media3TransformerTranscoder(private val context: Context) : VideoTranscoder {
 *     override fun reduce(input: File): TranscodeResult {
 *         val out = File(context.cacheDir, "vmodal_reduced_${'$'}{input.name}")
 *         // Reuse a prior reduction to skip re-encoding the same clip.
 *         if (out.isFile && out.length() > 0) return TranscodeResult(out, reused = true)
 *
 *         val edited = EditedMediaItem.Builder(MediaItem.fromUri(input.toURI().toString()))
 *             .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(360))))
 *             .build()
 *         val error = AtomicReference<Exception>()
 *         val latch = CountDownLatch(1)
 *         val transformer = Transformer.Builder(context)
 *             .addListener(object : Transformer.Listener {
 *                 override fun onCompleted(c: Composition, r: ExportResult) = latch.countDown()
 *                 override fun onError(c: Composition, r: ExportResult, e: ExportException) {
 *                     error.set(e); latch.countDown()
 *                 }
 *             })
 *             .build()
 *         // Transformer.start() must be called on the main thread; dispatch this reduce() call
 *         // from a main-thread coroutine (for example withContext(Dispatchers.Main)).
 *         transformer.start(edited, out.absolutePath)
 *         latch.await()
 *         error.get()?.let { throw it }
 *         return TranscodeResult(out, reused = false)
 *     }
 * }
 *
 * // 3. Inject it per upload (default stays "no transcoding"):
 * val options = VideoUploadOptions().apply { transcoder = Media3TransformerTranscoder(context) }
 * val response = sdk.collections.videoUpload(
 *     UploadSource.fromFile(file), "collection", "stream", options = options,
 * )
 * // response.reduceSize == true; response.sourceSizeBytes == original bytes;
 * // response.sizeBytes == uploaded (reduced) bytes; the produced temp is deleted afterward.
 * ```
 *
 * ## Implementation contract
 *
 * - Return a **new** file at a different path (a disposable temp, for example under
 *   `context.cacheDir`) when you actually reduce the input. The upload lifecycle deletes that
 *   produced file after a successful upload; the app's original file is never touched.
 * - Return the **input file unchanged** to pass through (no upload-side deletion happens).
 * - Set [TranscodeResult.reused] to `true` when you serve a previously cached reduction.
 * - Do not use an ffmpeg CLI binary on Android: there is no system `ffmpeg`, and app sandboxing
 *   blocks executing a bundled one. Prefer Media3 Transformer or `MediaCodec`.
 */
interface VideoTranscoder {
    /** Returns a reduced copy of [input], or [input] itself to pass through unchanged. */
    fun reduce(input: File): TranscodeResult

    /** True when [reduce] always returns the input unchanged. Lets the lifecycle skip transcoding. */
    val isPassthrough: Boolean get() = false
}

/** Identity transcoder that uploads the original bytes. This is the SDK default: no transcoding. */
object PassthroughTranscoder : VideoTranscoder {
    override fun reduce(input: File): TranscodeResult = TranscodeResult(input, reused = false)
    override val isPassthrough: Boolean = true
}

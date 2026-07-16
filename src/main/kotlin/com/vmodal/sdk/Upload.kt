package com.vmodal.sdk

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reopenable media input used by signed uploads.
 *
 * The SDK intentionally does not depend on android.net.Uri. Keeping the Android framework out of
 * the core artifact makes it JVM-testable, while an app can still stream a content URI with:
 * `UploadSource(name, size, mime, uri.toString()) { resolver.openInputStream(uri)!! }`.
 */
class UploadSource(
    val fileName: String,
    val contentLength: Long,
    val contentType: String = guessContentType(fileName),
    val sourceId: String = fileName,
    val versionTag: String = "",
    private val rangeOpener: ((Long) -> InputStream)? = null,
    private val opener: () -> InputStream,
) {
    init {
        strMultipartValue("file name", fileName, 1_024)
        strMultipartValue("content type", contentType, 255)
        if (contentLength < 0) throw ValidationFailed("content_length must be known for a signed upload")
    }

    fun open(offset: Long = 0, length: Long = contentLength - offset): InputStream {
        if (offset < 0 || length < 0 || offset + length > contentLength) {
            throw ValidationFailed("upload source range is invalid")
        }
        val direct = rangeOpener
        val input = direct?.invoke(offset) ?: opener()
        try {
            if (direct == null) input.skipFully(offset)
            return LimitedInputStream(input, length)
        } catch (exc: Exception) {
            input.close()
            throw exc
        }
    }

    companion object {
        fun fromFile(file: File, contentType: String = guessContentType(file.name)): UploadSource {
            if (!file.isFile) throw ValidationFailed("file must exist: ${file.path}")
            return UploadSource(
                file.name,
                file.length(),
                contentType,
                file.absolutePath,
                "${file.length()}:${file.lastModified()}",
                { offset -> FileInputStream(file).apply { channel.position(offset) } },
            ) { file.inputStream() }
        }
    }
}

data class UploadProgress(val uploadedBytes: Long, val totalBytes: Long) {
    val percent: Int = if (totalBytes <= 0) 0 else ((uploadedBytes * 100) / totalBytes).coerceIn(0, 100).toInt()
}

data class SignedUploadResult(
    val statusCode: Int,
    val etag: String = "",
    val localMd5: String = "",
)

class UploadHandle internal constructor() {
    private val canceled = AtomicBoolean(false)
    private val calls = CopyOnWriteArrayList<Call>()

    val isCanceled: Boolean get() = canceled.get()

    fun cancel() {
        canceled.set(true)
        calls.forEach { it.cancel() }
        calls.clear()
    }

    internal fun add(call: Call) {
        calls += call
        if (isCanceled) call.cancel()
    }

    internal fun remove(call: Call) {
        calls -= call
    }

    internal fun ensureActive() {
        if (isCanceled) throw ApiError("upload canceled")
    }

    internal val activeCallCount: Int get() = calls.size
}

interface SignedUploadTransport {
    fun enqueue(
        source: UploadSource,
        url: String,
        method: String = "PUT",
        offset: Long = 0,
        length: Long = source.contentLength,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Long? = null,
        handle: UploadHandle = UploadHandle(),
        onProgress: (UploadProgress) -> Unit = {},
        onSuccess: (SignedUploadResult) -> Unit,
        onFailure: (Exception) -> Unit,
    ): UploadHandle
}

class OkHttpSignedUploadTransport(
    timeoutMillis: Long = 300_000,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build(),
) : SignedUploadTransport {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun enqueue(
        source: UploadSource,
        url: String,
        method: String,
        offset: Long,
        length: Long,
        headers: Map<String, String>,
        timeoutMillis: Long?,
        handle: UploadHandle,
        onProgress: (UploadProgress) -> Unit,
        onSuccess: (SignedUploadResult) -> Unit,
        onFailure: (Exception) -> Unit,
    ): UploadHandle {
        if (url.isBlank()) throw ValidationFailed("signed upload URL is empty")
        validatedHttpUrl(url)
        val body = StreamRequestBody(source, offset, length, onProgress)
        val builder = Request.Builder().url(url).method(method.uppercase(), body)
        // A pre-signed URL is already the authorization. Forwarding the SDK bearer/user headers
        // would leak unrelated credentials to object storage and can invalidate the signature.
        headers.filterKeys { it.lowercase() !in AUTH_HEADERS }.forEach { (key, value) -> builder.header(key, value) }
        builder.header("Content-Type", source.contentType)
        builder.header("Content-Length", length.toString())
        // Multipart parts are intentionally allowed a longer timeout than small API calls.
        // Deriving a client preserves the shared connection pool while applying that part policy.
        val net = if (timeoutMillis == null) client else client.newBuilder()
            .readTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val call = net.newCall(builder.build())
        handle.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handle.remove(call)
                onFailure(SignedUploadFailure(e, body.sentBytes, body.localMd5))
            }

            override fun onResponse(call: Call, response: Response) {
                handle.remove(call)
                response.use {
                    if (!response.isSuccessful) {
                        val err = try {
                            val errBody = response.body
                            val bytes = errBody?.byteStream()?.use { input ->
                                input.bytesBounded(errBody.contentLength(), ERROR_RESPONSE_LIMIT_BYTES)
                            } ?: ByteArray(0)
                            ApiError("signed upload failed", response.code, bytes.toString(StandardCharsets.UTF_8))
                        } catch (exc: Exception) {
                            exc
                        }
                        onFailure(err)
                        return
                    }
                    val etag = response.header("ETag").orEmpty().trim().trim('"')
                    onSuccess(SignedUploadResult(response.code, etag, body.localMd5))
                }
            }
        })
        return handle
    }

    private companion object {
        val AUTH_HEADERS = setOf("authorization", "x-user-id", "x-tenant-id", "x-user-email")
    }
}

private class StreamRequestBody(
    private val source: UploadSource,
    private val offset: Long,
    private val length: Long,
    private val onProgress: (UploadProgress) -> Unit,
) : RequestBody() {
    @Volatile
    var sentBytes: Long = 0
        private set

    @Volatile
    var localMd5: String = ""
        private set

    override fun contentType() = source.contentType.toMediaType()
    override fun contentLength(): Long = length
    override fun isOneShot(): Boolean = false

    override fun writeTo(sink: BufferedSink) {
        val digest = MessageDigest.getInstance("MD5")
        val buf = ByteArray(STREAM_BUFFER_BYTES)
        var sent = 0L
        sentBytes = 0
        localMd5 = ""
        source.open(offset, length).use { input ->
            while (sent < length) {
                val count = input.read(buf, 0, minOf(buf.size.toLong(), length - sent).toInt())
                if (count < 0) throw IOException("upload source ended early")
                sink.write(buf, 0, count)
                digest.update(buf, 0, count)
                sent += count
                sentBytes = sent
                onProgress(UploadProgress(sent, length))
            }
        }
        localMd5 = digest.digest().strHex()
    }
}

internal class SignedUploadFailure(
    @Suppress("UNUSED_PARAMETER") cause: IOException,
    val sentBytes: Long,
    val localMd5: String,
) : IOException("signed upload transport error")

private class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val value = input.read()
        if (value >= 0) left--
        return value
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val count = input.read(buf, off, minOf(len.toLong(), left).toInt())
        if (count > 0) left -= count
        return count
    }

    override fun close() = input.close()
}

internal fun md5Hex(source: UploadSource, offset: Long, length: Long): String {
    val digest = MessageDigest.getInstance("MD5")
    val buf = ByteArray(STREAM_BUFFER_BYTES)
    source.open(offset, length).use { input ->
        while (true) {
            val count = input.read(buf)
            if (count < 0) break
            digest.update(buf, 0, count)
        }
    }
    return digest.digest().strHex()
}

interface UploadSessionStore {
    fun load(key: String): Map<String, Any?>?
    fun save(key: String, value: Map<String, Any?>)
    fun remove(key: String)
}

class MemoryUploadSessionStore : UploadSessionStore {
    private val values = ConcurrentHashMap<String, Map<String, Any?>>()

    override fun load(key: String): Map<String, Any?>? = values[key]
    override fun save(key: String, value: Map<String, Any?>) {
        values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
}

class FileUploadSessionStore(private val directory: File) : UploadSessionStore {
    init {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("cannot create upload checkpoint directory: ${directory.path}")
        if (!directory.isDirectory) throw IOException("upload checkpoint path is not a directory: ${directory.path}")
    }

    @Synchronized
    override fun load(key: String): Map<String, Any?>? {
        val file = file(key)
        val source = if (file.isFile) file else backup(file).takeIf { it.isFile } ?: return null
        val bytes = source.inputStream().use { it.bytesBounded(source.length(), CHECKPOINT_JSON_LIMIT_BYTES) }
        val raw = VmodalJson.parse(bytes.toString(StandardCharsets.UTF_8)) as? Map<*, *>
            ?: throw MalformedResponse("upload checkpoint is invalid")
        return raw.entries.associate { it.key.toString() to it.value }
    }

    @Synchronized
    override fun save(key: String, value: Map<String, Any?>) {
        val file = file(key)
        val temp = File(directory, ".${file.name}.${Thread.currentThread().id}.tmp")
        val backup = backup(file)
        val text = VmodalJson.stringify(value)
        val size = jsonUtf8Size(text, CHECKPOINT_JSON_LIMIT_BYTES)
        if (size > CHECKPOINT_JSON_LIMIT_BYTES) throw ResponseTooLarge(CHECKPOINT_JSON_LIMIT_BYTES, size)
        temp.writeText(text)
        if (backup.exists() && !backup.delete()) throw IOException("cannot replace upload checkpoint backup: ${backup.path}")
        if (file.exists() && !file.renameTo(backup)) throw IOException("cannot rotate upload checkpoint: ${file.path}")
        if (!temp.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file)
            temp.delete()
            throw IOException("cannot save upload checkpoint: ${file.path}")
        }
        if (backup.exists()) backup.delete()
    }

    @Synchronized
    override fun remove(key: String) {
        val file = file(key)
        if (file.exists() && !file.delete()) throw IOException("cannot delete upload checkpoint: ${file.path}")
        val backup = backup(file)
        if (backup.exists() && !backup.delete()) throw IOException("cannot delete upload checkpoint backup: ${backup.path}")
    }

    private fun file(key: String) = File(directory, strSha256(key) + ".json")
    private fun backup(file: File) = File(directory, file.name + ".bak")
}

object UploadSessionStores {
    val memory: UploadSessionStore = MemoryUploadSessionStore()
}

private const val STREAM_BUFFER_BYTES = 256 * 1024
internal const val CHECKPOINT_JSON_LIMIT_BYTES = 1L * 1024 * 1024

private fun InputStream.skipFully(offset: Long) {
    var left = offset
    var buf: ByteArray? = null
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            val data = buf ?: ByteArray(minOf(STREAM_BUFFER_BYTES.toLong(), left).toInt()).also { buf = it }
            val count = read(data, 0, minOf(data.size.toLong(), left).toInt())
            if (count < 0) throw IOException("upload source ended before offset")
            left -= count
        }
    }
}

private fun ByteArray.strHex(): String = joinToString("") { "%02x".format(it) }

internal fun strSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .strHex()

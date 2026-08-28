package com.vmodal.sdk

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Handle returned by a cancellable transport request. */
fun interface VmodalCancelHandle {
    /** Cancels the request. Calling this method more than once has no additional effect. */
    fun cancel()
}

/** Exactly-once callback for [CancellableVmodalTransport]. */
interface VmodalTransportCallback {
    /** Delivers the bounded HTTP response. */
    fun onSuccess(response: VmodalResponse)

    /** Delivers the transport failure or request cancellation. */
    fun onFailure(error: Throwable)
}

/** Additive transport capability for requests that can cancel their underlying HTTP call. */
interface CancellableVmodalTransport : VmodalTransport {
    /** Starts [request] and returns a handle that cancels its underlying call. */
    fun executeAsync(request: VmodalRequest, callback: VmodalTransportCallback): VmodalCancelHandle
}

/** Executes one upload gateway request while linking its transport call to [uploadHandle]. */
internal fun VmodalTransport.executeWithHandle(
    request: VmodalRequest,
    uploadHandle: UploadHandle,
): VmodalResponse {
    uploadHandle.ensureActive()
    if (this !is CancellableVmodalTransport) {
        return execute(request).also { uploadHandle.ensureActive() }
    }

    val done = AtomicBoolean(false)
    val result = AtomicReference<VmodalResponse?>()
    val failure = AtomicReference<Throwable?>()
    val requestHandle = AtomicReference<VmodalCancelHandle?>()
    val latch = CountDownLatch(1)
    val callback = object : VmodalTransportCallback {
        override fun onSuccess(response: VmodalResponse) {
            if (done.compareAndSet(false, true)) {
                result.set(response)
                latch.countDown()
            }
        }

        override fun onFailure(error: Throwable) {
            if (done.compareAndSet(false, true)) {
                failure.set(error)
                latch.countDown()
            }
        }
    }
    try {
        val active = executeAsync(request, callback)
        requestHandle.set(active)
        uploadHandle.add(active)
        if (done.get()) uploadHandle.remove(active)
        while (!latch.await(50, TimeUnit.MILLISECONDS)) uploadHandle.ensureActive()
        uploadHandle.ensureActive()
        failure.get()?.let { throw it }
        return result.get() ?: throw ApiError("gateway request returned no result")
    } catch (exc: InterruptedException) {
        requestHandle.get()?.cancel()
        Thread.currentThread().interrupt()
        throw ApiError("gateway request interrupted").also { it.initCause(exc) }
    } finally {
        requestHandle.get()?.let(uploadHandle::remove)
    }
}

/** Executes cancellable transports natively and legacy transports on [fallbackDispatcher]. */
internal suspend fun VmodalTransport.executeCancellable(
    request: VmodalRequest,
    fallbackDispatcher: CoroutineDispatcher,
): VmodalResponse {
    if (this !is CancellableVmodalTransport) return withContext(fallbackDispatcher) { execute(request) }
    return suspendCancellableCoroutine { continuation ->
        val done = AtomicBoolean(false)
        val handle = AtomicReference<VmodalCancelHandle?>()
        continuation.invokeOnCancellation {
            if (done.compareAndSet(false, true)) handle.get()?.cancel()
        }
        val callback = object : VmodalTransportCallback {
            override fun onSuccess(response: VmodalResponse) {
                if (done.compareAndSet(false, true)) continuation.resume(response)
            }

            override fun onFailure(error: Throwable) {
                if (done.compareAndSet(false, true)) continuation.resumeWithException(error)
            }
        }
        try {
            handle.set(executeAsync(request, callback))
            if (!continuation.isActive && done.get()) handle.get()?.cancel()
        } catch (error: Throwable) {
            if (done.compareAndSet(false, true)) continuation.resumeWithException(error)
        }
    }
}

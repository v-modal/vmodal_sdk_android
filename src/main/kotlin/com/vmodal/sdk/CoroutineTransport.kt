package com.vmodal.sdk

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

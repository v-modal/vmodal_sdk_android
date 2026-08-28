package com.vmodal.sdk

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class UploadResourceSnapshot(
    val activeOperations: Int,
    val queuedOperations: Int,
    val activeDataTasks: Int,
    val queuedDataTasks: Int,
)

internal interface UploadScheduler : AutoCloseable {
    fun execute(handle: UploadHandle, task: () -> Unit): Boolean
    fun <T> runBlocking(handle: UploadHandle, task: () -> T): T
    fun <T> submitData(task: Callable<T>): Future<T>
    fun snapshot(): UploadResourceSnapshot
}

internal class BoundedUploadScheduler(
    orchestrationWorkers: Int = 4,
    operationQueueSize: Int = 64,
    operationActiveLimit: Int = 8,
    dataWorkers: Int = 8,
    dataQueueSize: Int = 64,
) : UploadScheduler {
    private val closed = AtomicBoolean()
    private val opTotal = Semaphore(operationActiveLimit + operationQueueSize)
    private val opActive = Semaphore(operationActiveLimit)
    private val dataTotal = Semaphore(dataWorkers + dataQueueSize)
    private val activeOps = AtomicInteger()
    private val queuedOps = AtomicInteger()
    private val activeData = AtomicInteger()
    private val queuedData = AtomicInteger()
    private val orchestration = executor(orchestrationWorkers, operationQueueSize, "vmodal-upload-orchestration")
    private val data = executor(dataWorkers, dataQueueSize, "vmodal-upload-data")

    init {
        require(orchestrationWorkers > 0 && operationQueueSize > 0 && operationActiveLimit > 0)
        require(dataWorkers > 0 && dataQueueSize > 0)
    }

    override fun execute(handle: UploadHandle, task: () -> Unit): Boolean {
        if (closed.get() || !opTotal.tryAcquire()) return false
        queuedOps.incrementAndGet()
        return try {
            orchestration.execute worker@{
                queuedOps.decrementAndGet()
                if (handle.isStopped) {
                    opTotal.release()
                    return@worker
                }
                var active = false
                try {
                    opActive.acquire()
                    active = true
                    activeOps.incrementAndGet()
                    task()
                } catch (exc: InterruptedException) {
                    handle.stop()
                    Thread.currentThread().interrupt()
                } finally {
                    if (active) {
                        activeOps.decrementAndGet()
                        opActive.release()
                    }
                    opTotal.release()
                }
            }
            true
        } catch (exc: RuntimeException) {
            queuedOps.decrementAndGet()
            opTotal.release()
            false
        }
    }

    override fun <T> runBlocking(handle: UploadHandle, task: () -> T): T {
        try {
            opTotal.acquire()
        } catch (exc: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiError("upload admission interrupted").also { it.initCause(exc) }
        }
        queuedOps.incrementAndGet()
        try {
            opActive.acquire()
        } catch (exc: InterruptedException) {
            queuedOps.decrementAndGet()
            opTotal.release()
            Thread.currentThread().interrupt()
            throw ApiError("upload admission interrupted").also { it.initCause(exc) }
        }
        queuedOps.decrementAndGet()
        activeOps.incrementAndGet()
        return try {
            handle.ensureActive()
            task()
        } finally {
            activeOps.decrementAndGet()
            opActive.release()
            opTotal.release()
        }
    }

    override fun <T> submitData(task: Callable<T>): Future<T> {
        dataTotal.acquire()
        val future = CompletableFuture<T>()
        queuedData.incrementAndGet()
        try {
            data.execute worker@{
                queuedData.decrementAndGet()
                if (future.isCancelled) {
                    dataTotal.release()
                    return@worker
                }
                activeData.incrementAndGet()
                try {
                    future.complete(task.call())
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                } finally {
                    activeData.decrementAndGet()
                    dataTotal.release()
                }
            }
        } catch (exc: RuntimeException) {
            queuedData.decrementAndGet()
            dataTotal.release()
            future.completeExceptionally(exc)
        }
        return future
    }

    override fun snapshot() = UploadResourceSnapshot(
        activeOps.get(),
        queuedOps.get(),
        activeData.get(),
        queuedData.get(),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        orchestration.shutdownNow()
        data.shutdownNow()
        orchestration.awaitTermination(5, TimeUnit.SECONDS)
        data.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun executor(workers: Int, queueSize: Int, name: String) = ThreadPoolExecutor(
        workers,
        workers,
        30,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueSize),
        { task -> Thread(task, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
}

internal val processUploadScheduler: UploadScheduler = BoundedUploadScheduler()

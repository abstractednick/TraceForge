package io.traceforge.runtime

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

/**
 * Bounded asynchronous recorder. Application threads never perform file I/O unless [OverflowStrategy.BLOCK]
 * is explicitly selected and the queue is full.
 */
class TraceRecorder(
    private val config: TraceForgeConfig,
    private val sink: TraceEventSink
) : Closeable {
    private val queue = ArrayBlockingQueue<TraceEvent>(config.bufferCapacity)
    private val accepting = AtomicBoolean(true)
    private val failure = AtomicReference<Throwable?>()
    private val dropped = LongAdder()
    private val worker = Thread(::consume, "traceforge-writer").apply {
        isDaemon = true
        start()
    }

    val droppedEventCount: Long
        get() = dropped.sum()

    val writerFailure: Throwable?
        get() = failure.get()

    fun record(event: TraceEvent, className: String? = null): Boolean {
        if (!accepting.get() || !config.tracingEnabled) return false
        if (className != null && !config.acceptsClass(className)) return false
        if (!config.acceptsThread(event.threadName)) return false
        if (config.samplingRate < 1.0 &&
            ThreadLocalRandom.current().nextDouble() >= config.samplingRate
        ) {
            return false
        }

        val accepted = when (config.overflowStrategy) {
            OverflowStrategy.DROP_LATEST -> queue.offer(event)
            OverflowStrategy.DROP_OLDEST -> {
                if (queue.offer(event)) {
                    true
                } else {
                    if (queue.poll() != null) dropped.increment()
                    queue.offer(event)
                }
            }

            OverflowStrategy.BLOCK -> putInterruptibly(event)
        }
        if (!accepted) dropped.increment()
        return accepted
    }

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        worker.interrupt()
        worker.join(CLOSE_TIMEOUT_MILLIS)
        if (worker.isAlive) {
            failure.compareAndSet(null, IllegalStateException("Trace writer did not stop in time"))
        }
        flushAndCloseSink()
    }

    private fun putInterruptibly(event: TraceEvent): Boolean = try {
        queue.put(event)
        true
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun consume() {
        val flushNanos = config.flushInterval.toNanos()
        var lastFlush = System.nanoTime()
        try {
            while (accepting.get() || queue.isNotEmpty()) {
                val event = try {
                    queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    null
                }
                if (event != null) sink.accept(event)
                val now = System.nanoTime()
                if (now - lastFlush >= flushNanos) {
                    (sink as? JsonlTraceEventWriter)?.flush()
                    lastFlush = now
                }
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
            accepting.set(false)
        }
    }

    private fun flushAndCloseSink() {
        try {
            (sink as? JsonlTraceEventWriter)?.flush()
            (sink as? Closeable)?.close()
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        }
    }

    private companion object {
        const val POLL_MILLIS = 100L
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}

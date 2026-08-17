package io.traceforge.runtime

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** A pluggable bridge implemented by a JVM instrumentation agent. */
fun interface AgentAttacher {
    @Throws(Exception::class)
    fun attach(config: TraceForgeConfig): AgentAttachment
}

/** Handle returned by an [AgentAttacher] and released when tracing stops. */
fun interface AgentAttachment : Closeable

/** Current process-wide TraceForge lifecycle state. */
enum class TraceForgeState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED
}

/**
 * Process-wide runtime entry point. Lifecycle transitions are serialized and capture operations
 * use volatile snapshots, keeping the hot path free from global locks.
 */
object TraceForge {
    private val lifecycleLock = Any()
    private val nextSectionId = AtomicLong()
    private val sections = ThreadLocal.withInitial { ArrayDeque<SectionFrame>() }

    @Volatile
    private var currentState = TraceForgeState.STOPPED

    @Volatile
    private var activeConfig: TraceForgeConfig? = null

    @Volatile
    private var recorder: TraceRecorder? = null

    private var attachment: AgentAttachment? = null

    val state: TraceForgeState
        get() = currentState

    val config: TraceForgeConfig?
        get() = activeConfig

    val droppedEventCount: Long
        get() = recorder?.droppedEventCount ?: 0L

    /**
     * Starts tracing and creates/truncates [TraceForgeConfig.outputPath].
     *
     * Calling this while tracing is active is a programming error. If agent attachment fails,
     * resources are closed, state becomes [TraceForgeState.FAILED], and the exception is rethrown.
     */
    @JvmOverloads
    fun start(
        config: TraceForgeConfig = TraceForgeConfig(),
        attacher: AgentAttacher = AgentAttacher { AgentAttachment {} }
    ) {
        synchronized(lifecycleLock) {
            check(currentState == TraceForgeState.STOPPED || currentState == TraceForgeState.FAILED) {
                "TraceForge cannot start from state $currentState"
            }
            currentState = TraceForgeState.STARTING
            val newRecorder = TraceRecorder(config, JsonlTraceEventWriter(config.outputPath))
            try {
                val newAttachment = attacher.attach(config)
                activeConfig = config
                recorder = newRecorder
                attachment = newAttachment
                currentState = TraceForgeState.RUNNING
            } catch (error: Throwable) {
                newRecorder.close()
                activeConfig = null
                recorder = null
                attachment = null
                currentState = TraceForgeState.FAILED
                throw error
            }
        }
    }

    /** Stops capture, drains buffered events, detaches the agent, and closes output. */
    fun stop() {
        synchronized(lifecycleLock) {
            if (currentState == TraceForgeState.STOPPED) return
            check(currentState == TraceForgeState.RUNNING || currentState == TraceForgeState.FAILED) {
                "TraceForge cannot stop from state $currentState"
            }
            currentState = TraceForgeState.STOPPING
            var failure: Throwable? = null
            try {
                attachment?.close()
            } catch (error: Throwable) {
                failure = error
            }
            recorder?.close()
            failure = failure ?: recorder?.writerFailure
            attachment = null
            recorder = null
            activeConfig = null
            sections.remove()
            currentState = if (failure == null) TraceForgeState.STOPPED else TraceForgeState.FAILED
            if (failure != null) throw IllegalStateException("Failed to stop TraceForge cleanly", failure)
        }
    }

    /** Records an instantaneous marker when marker tracing is enabled. */
    @JvmOverloads
    fun mark(name: String, attributes: Map<String, String> = emptyMap()): Boolean {
        require(name.isNotBlank()) { "name cannot be blank" }
        val configSnapshot = activeConfig ?: return false
        if (!configSnapshot.traceMarkers) return false
        val thread = Thread.currentThread()
        return recorder?.record(
            MarkerEvent(
                name = name,
                timestampNanos = System.nanoTime(),
                threadId = thread.id,
                threadName = thread.name,
                attributes = attributes.toMap()
            )
        ) ?: false
    }

    /**
     * Traces [block] as a nested section. Completion is emitted from a `finally` path and thrown
     * exceptions propagate unchanged.
     */
    fun <T> traceSection(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        block: () -> T
    ): T {
        require(name.isNotBlank()) { "name cannot be blank" }
        val configSnapshot = activeConfig
        val recorderSnapshot = recorder
        if (configSnapshot?.traceSections != true || recorderSnapshot == null) return block()

        val stack = sections.get()
        val frame = SectionFrame(
            id = nextSectionId.incrementAndGet(),
            parentId = stack.peekLast()?.id,
            name = name,
            startedNanos = System.nanoTime(),
            attributes = attributes.toMap()
        )
        stack.addLast(frame)
        var failed = false
        try {
            return block()
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            val endedNanos = System.nanoTime()
            check(stack.removeLast() === frame) { "Trace section stack was corrupted" }
            if (stack.isEmpty()) sections.remove()
            val thread = Thread.currentThread()
            recorderSnapshot.record(
                SectionEvent(
                    id = frame.id,
                    parentId = frame.parentId,
                    name = frame.name,
                    timestampNanos = frame.startedNanos,
                    durationNanos = endedNanos - frame.startedNanos,
                    threadId = thread.id,
                    threadName = thread.name,
                    attributes = frame.attributes,
                    failed = failed
                )
            )
        }
    }

    private data class SectionFrame(
        val id: Long,
        val parentId: Long?,
        val name: String,
        val startedNanos: Long,
        val attributes: Map<String, String>
    )
}

/** Kotlin-friendly top-level section API. */
fun <T> traceSection(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    block: () -> T
): T = TraceForge.traceSection(name, attributes, block)

/** Kotlin-friendly top-level marker API. */
fun traceMarker(name: String, attributes: Map<String, String> = emptyMap()): Boolean =
    TraceForge.mark(name, attributes)

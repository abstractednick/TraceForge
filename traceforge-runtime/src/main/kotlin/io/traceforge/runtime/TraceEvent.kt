package io.traceforge.runtime

/**
 * A persisted trace record. Nanosecond values are monotonic JVM time and are meaningful only
 * relative to other events in the same process.
 */
sealed interface TraceEvent {
    val timestampNanos: Long
    val threadId: Long
    val threadName: String
    val attributes: Map<String, String>
}

data class SectionEvent(
    val id: Long,
    val parentId: Long?,
    val name: String,
    override val timestampNanos: Long,
    val durationNanos: Long,
    override val threadId: Long,
    override val threadName: String,
    override val attributes: Map<String, String> = emptyMap(),
    val failed: Boolean = false
) : TraceEvent {
    init {
        require(id > 0) { "id must be positive" }
        require(name.isNotBlank()) { "name cannot be blank" }
        require(durationNanos >= 0) { "durationNanos cannot be negative" }
    }
}

data class MarkerEvent(
    val name: String,
    override val timestampNanos: Long,
    override val threadId: Long,
    override val threadName: String,
    override val attributes: Map<String, String> = emptyMap()
) : TraceEvent {
    init {
        require(name.isNotBlank()) { "name cannot be blank" }
    }
}

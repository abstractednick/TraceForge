package io.traceforge.runtime

import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Immutable runtime configuration. Filtering is intentionally prefix based for packages and
 * regular-expression based for thread names so it remains deterministic and allocation-light.
 */
data class TraceForgeConfig(
    val outputPath: Path = Paths.get("traceforge-traces", "trace.jsonl"),
    val tracingEnabled: Boolean = true,
    val traceSections: Boolean = true,
    val traceMarkers: Boolean = true,
    val includedPackagePrefixes: Set<String> = emptySet(),
    val excludedPackagePrefixes: Set<String> = setOf("java.", "javax.", "sun.", "kotlin."),
    val includedThreadNames: List<Regex> = emptyList(),
    val excludedThreadNames: List<Regex> = emptyList(),
    val bufferCapacity: Int = 16_384,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.DROP_OLDEST,
    val samplingRate: Double = 1.0,
    val flushInterval: Duration = Duration.ofSeconds(1)
) {
    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive" }
        require(samplingRate in 0.0..1.0) { "samplingRate must be between 0.0 and 1.0" }
        require(!flushInterval.isNegative && !flushInterval.isZero) {
            "flushInterval must be positive"
        }
        require(includedPackagePrefixes.none(String::isBlank)) {
            "includedPackagePrefixes cannot contain blank values"
        }
        require(excludedPackagePrefixes.none(String::isBlank)) {
            "excludedPackagePrefixes cannot contain blank values"
        }
    }

    /** Returns whether an instrumented class should produce events. */
    fun acceptsClass(className: String): Boolean {
        if (excludedPackagePrefixes.any(className::startsWith)) return false
        return includedPackagePrefixes.isEmpty() ||
            includedPackagePrefixes.any(className::startsWith)
    }

    /** Returns whether events from [threadName] should be retained. */
    fun acceptsThread(threadName: String): Boolean {
        if (excludedThreadNames.any { it.matches(threadName) }) return false
        return includedThreadNames.isEmpty() ||
            includedThreadNames.any { it.matches(threadName) }
    }
}

/** Defines behavior when the bounded in-memory event queue is full. */
enum class OverflowStrategy {
    DROP_OLDEST,
    DROP_LATEST,
    BLOCK
}

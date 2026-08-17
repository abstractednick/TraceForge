package io.traceforge.runtime

/** Aggregated timing information for sections sharing a name. */
data class Hotspot(
    val name: String,
    val callCount: Long,
    val failedCallCount: Long,
    val inclusiveNanos: Long,
    val selfNanos: Long,
    val maxInclusiveNanos: Long
) {
    val averageInclusiveNanos: Double
        get() = if (callCount == 0L) 0.0 else inclusiveNanos.toDouble() / callCount
}

/**
 * Computes inclusive and self time. Direct-child intervals are merged before subtraction so
 * malformed or concurrently assembled inputs cannot produce negative self time.
 */
object HotspotAnalyzer {
    fun analyze(events: Iterable<SectionEvent>): List<Hotspot> {
        val sections = events.toList()
        val childrenByParent = sections
            .filter { it.parentId != null }
            .groupBy { requireNotNull(it.parentId) }
        val aggregates = linkedMapOf<String, MutableHotspot>()

        sections.forEach { section ->
            val childTime = coveredChildTime(section, childrenByParent[section.id].orEmpty())
            val aggregate = aggregates.getOrPut(section.name) { MutableHotspot(section.name) }
            aggregate.callCount++
            if (section.failed) aggregate.failedCallCount++
            aggregate.inclusiveNanos += section.durationNanos
            aggregate.selfNanos += (section.durationNanos - childTime).coerceAtLeast(0)
            aggregate.maxInclusiveNanos =
                maxOf(aggregate.maxInclusiveNanos, section.durationNanos)
        }

        return aggregates.values
            .map(MutableHotspot::freeze)
            .sortedWith(
                compareByDescending<Hotspot> { it.selfNanos }
                    .thenByDescending { it.inclusiveNanos }
                    .thenBy { it.name }
            )
    }

    private fun coveredChildTime(parent: SectionEvent, children: List<SectionEvent>): Long {
        if (children.isEmpty()) return 0
        val parentEnd = saturatedAdd(parent.timestampNanos, parent.durationNanos)
        val intervals = children.mapNotNull { child ->
            val start = maxOf(parent.timestampNanos, child.timestampNanos)
            val end = minOf(parentEnd, saturatedAdd(child.timestampNanos, child.durationNanos))
            if (end > start) start to end else null
        }.sortedBy(Pair<Long, Long>::first)
        if (intervals.isEmpty()) return 0

        var covered = 0L
        var currentStart = intervals.first().first
        var currentEnd = intervals.first().second
        intervals.drop(1).forEach { (start, end) ->
            if (start <= currentEnd) {
                currentEnd = maxOf(currentEnd, end)
            } else {
                covered += currentEnd - currentStart
                currentStart = start
                currentEnd = end
            }
        }
        return covered + (currentEnd - currentStart)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class MutableHotspot(
        val name: String,
        var callCount: Long = 0,
        var failedCallCount: Long = 0,
        var inclusiveNanos: Long = 0,
        var selfNanos: Long = 0,
        var maxInclusiveNanos: Long = 0
    ) {
        fun freeze() = Hotspot(
            name = name,
            callCount = callCount,
            failedCallCount = failedCallCount,
            inclusiveNanos = inclusiveNanos,
            selfNanos = selfNanos,
            maxInclusiveNanos = maxInclusiveNanos
        )
    }
}

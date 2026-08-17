package io.traceforge.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class HotspotAnalyzerTest {
    @Test
    fun `analysis subtracts merged direct child intervals from self time`() {
        val events = listOf(
            section(id = 1, name = "parent", start = 100, duration = 100),
            section(id = 2, parentId = 1, name = "child", start = 110, duration = 30),
            section(id = 3, parentId = 1, name = "child", start = 130, duration = 40),
            section(id = 4, name = "parent", start = 300, duration = 50, failed = true)
        )

        val hotspots = HotspotAnalyzer.analyze(events)
        val parent = hotspots.single { it.name == "parent" }
        val child = hotspots.single { it.name == "child" }

        assertEquals(2, parent.callCount)
        assertEquals(1, parent.failedCallCount)
        assertEquals(150, parent.inclusiveNanos)
        assertEquals(90, parent.selfNanos)
        assertEquals(70, child.inclusiveNanos)
        assertEquals(70, child.selfNanos)
    }

    private fun section(
        id: Long,
        name: String,
        start: Long,
        duration: Long,
        parentId: Long? = null,
        failed: Boolean = false
    ) = SectionEvent(
        id = id,
        parentId = parentId,
        name = name,
        timestampNanos = start,
        durationNanos = duration,
        threadId = 1,
        threadName = "test",
        failed = failed
    )
}

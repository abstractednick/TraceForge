package io.traceforge.runtime

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TraceForgeRuntimeTest {
    @AfterTest
    fun stopRuntime() {
        if (TraceForge.state == TraceForgeState.RUNNING) TraceForge.stop()
    }

    @Test
    fun `configuration validates safety limits and filters names`() {
        val config = TraceForgeConfig(
            includedPackagePrefixes = setOf("example."),
            excludedPackagePrefixes = setOf("example.generated."),
            includedThreadNames = listOf(Regex("worker-\\d+")),
            excludedThreadNames = listOf(Regex("worker-13"))
        )

        assertTrue(config.acceptsClass("example.Service"))
        assertFalse(config.acceptsClass("example.generated.Proxy"))
        assertFalse(config.acceptsClass("other.Service"))
        assertTrue(config.acceptsThread("worker-2"))
        assertFalse(config.acceptsThread("worker-13"))
        assertFailsWith<IllegalArgumentException> {
            TraceForgeConfig(samplingRate = 1.01)
        }
    }

    @Test
    fun `json encoding escapes content and is deterministic`() {
        val encoded = TraceEventJson.encode(
            MarkerEvent(
                name = "line\n\"quoted\"",
                timestampNanos = 12,
                threadId = 4,
                threadName = "test",
                attributes = mapOf("z" to "last", "a" to "first")
            )
        )

        assertEquals(
            """{"type":"marker","name":"line\n\"quoted\"","timestampNanos":12,"threadId":4,"threadName":"test","attributes":{"a":"first","z":"last"}}""",
            encoded
        )
    }

    @Test
    fun `nested sections and failures are persisted before stop returns`() {
        val output = Files.createTempDirectory("traceforge-test").resolve("events.jsonl")
        TraceForge.start(TraceForgeConfig(outputPath = output))

        TraceForge.traceSection("outer", mapOf("request" to "42")) {
            TraceForge.mark("checkpoint")
            TraceForge.traceSection("inner") { "result" }
        }
        assertFailsWith<ExpectedFailure> {
            TraceForge.traceSection("failure") { throw ExpectedFailure() }
        }
        TraceForge.stop()

        val lines = Files.readAllLines(output)
        assertEquals(4, lines.size)
        val outer = assertNotNull(lines.singleOrNull { it.contains("\"name\":\"outer\"") })
        val inner = assertNotNull(lines.singleOrNull { it.contains("\"name\":\"inner\"") })
        assertTrue(outer.contains("\"parentId\"").not())
        assertTrue(inner.contains("\"parentId\""))
        assertTrue(lines.single { it.contains("\"name\":\"failure\"") }.contains("\"failed\":true"))
        assertEquals(TraceForgeState.STOPPED, TraceForge.state)
    }

    @Test
    fun `agent attachment follows runtime lifecycle`() {
        var attached = false
        var detached = false
        val output = Files.createTempDirectory("traceforge-agent-test").resolve("events.jsonl")

        TraceForge.start(TraceForgeConfig(outputPath = output), AgentAttacher {
            attached = true
            AgentAttachment { detached = true }
        })
        TraceForge.stop()

        assertTrue(attached)
        assertTrue(detached)
    }

    private class ExpectedFailure : RuntimeException()
}

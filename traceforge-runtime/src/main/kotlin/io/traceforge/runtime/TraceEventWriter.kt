package io.traceforge.runtime

import java.io.BufferedWriter
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Consumer abstraction used to decouple capture from persistence. */
fun interface TraceEventSink {
    fun accept(event: TraceEvent)
}

/** Thread-safe, dependency-free JSON Lines writer. */
class JsonlTraceEventWriter private constructor(
    private val writer: BufferedWriter
) : TraceEventSink, Closeable {
    private val lock = ReentrantLock()
    private var closed = false

    constructor(path: Path) : this(open(path))

    override fun accept(event: TraceEvent) {
        val json = TraceEventJson.encode(event)
        lock.withLock {
            check(!closed) { "Trace writer is closed" }
            writer.append(json)
            writer.newLine()
        }
    }

    fun flush() {
        lock.withLock {
            if (!closed) writer.flush()
        }
    }

    override fun close() {
        lock.withLock {
            if (!closed) {
                closed = true
                writer.close()
            }
        }
    }

    companion object {
        private fun open(path: Path): BufferedWriter {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            return Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        }
    }
}

/** Stable JSON encoder kept public for integrations that manage their own output transport. */
object TraceEventJson {
    fun encode(event: TraceEvent): String = buildString(256) {
        append('{')
        when (event) {
            is SectionEvent -> {
                field("type", "section")
                append(',')
                numberField("id", event.id)
                event.parentId?.let {
                    append(',')
                    numberField("parentId", it)
                }
                append(',')
                field("name", event.name)
                append(',')
                numberField("timestampNanos", event.timestampNanos)
                append(',')
                numberField("durationNanos", event.durationNanos)
                append(',')
                numberField("threadId", event.threadId)
                append(',')
                field("threadName", event.threadName)
                append(',')
                append("\"failed\":").append(event.failed)
                appendAttributes(event.attributes)
            }

            is MarkerEvent -> {
                field("type", "marker")
                append(',')
                field("name", event.name)
                append(',')
                numberField("timestampNanos", event.timestampNanos)
                append(',')
                numberField("threadId", event.threadId)
                append(',')
                field("threadName", event.threadName)
                appendAttributes(event.attributes)
            }
        }
        append('}')
    }

    private fun StringBuilder.appendAttributes(attributes: Map<String, String>) {
        append(",\"attributes\":{")
        attributes.toSortedMap().entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            field(entry.key, entry.value)
        }
        append('}')
    }

    private fun StringBuilder.field(name: String, value: String) {
        append('"').append(escape(name)).append("\":\"")
            .append(escape(value)).append('"')
    }

    private fun StringBuilder.numberField(name: String, value: Long) {
        append('"').append(name).append("\":").append(value)
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}

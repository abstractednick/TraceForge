package dev.traceforge.sample

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import io.traceforge.runtime.TraceForge
import io.traceforge.runtime.TraceForgeConfig
import io.traceforge.runtime.traceSection
import java.io.File

/**
 * Illustrative integration source, not a complete Android application.
 *
 * Package libtraceforge-agent.so for the active ABI and copy it to [agentLibrary] before attaching.
 */
class TraceForgeSampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isDebuggable()) return

        val trace = File(filesDir, "traceforge-events.jsonl")
        val agentLibrary = File(codeCacheDir, "libtraceforge-agent.so")
        if (!agentLibrary.isFile) return

        val options = listOf(
            "output=${trace.absolutePath}",
            "methods=true",
            "allocations=false",
            "include_package=dev.traceforge.sample",
            "max_events=8192",
        ).joinToString(",")

        Debug.attachJvmtiAgent(agentLibrary.absolutePath, options, classLoader)
    }

    private fun isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

class CheckoutScenario {
    fun runTraced(outputDirectory: File, items: List<String>): Int {
        TraceForge.start(
            TraceForgeConfig(
                outputPath = outputDirectory.toPath().resolve("runtime-sections.jsonl"),
                includedPackagePrefixes = setOf("dev.traceforge.sample"),
            )
        )
        return try {
            traceSection("checkout", mapOf("itemCount" to items.size.toString())) {
                val result = items.asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .sumOf(String::length)
                TraceForge.mark("checkout_complete")
                result
            }
        } finally {
            TraceForge.stop()
        }
    }
}

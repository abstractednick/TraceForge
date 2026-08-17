# TraceForge Runtime

Kotlin/JVM library that gives app developers a small, explicit API for:

- process-wide start/stop lifecycle
- nested `traceSection` annotations with `try`/`finally` completion
- instantaneous markers
- package and thread filtering, sampling, and bounded overflow strategies
- JSONL event writing without third-party dependencies
- inclusive/self-time hotspot analysis over recorded section events

## Usage

```kotlin
TraceForge.start(
    TraceForgeConfig(
        outputPath = Paths.get("traces", "run.jsonl"),
        includedPackagePrefixes = setOf("com.example"),
        bufferCapacity = 16_384,
    )
)

try {
    traceSection("checkout") {
        TraceForge.mark("payment_ok")
    }
} finally {
    TraceForge.stop()
}
```

Agent attachment is pluggable via `AgentAttacher`. The default attacher is a no-op so unit tests and
desktop analysis remain Android-SDK free. See the sample module for the intended
`Debug.attachJvmtiAgent` integration pattern.

## Build / test

From the repository root:

```bash
./gradlew :traceforge-runtime:test
```

# TraceForge sample

`TraceForgeSample.kt` demonstrates guarded JVMTI attachment and a small workload whose methods can
appear in a trace. It is intentionally source-only: this directory does not claim to be a buildable
Android application.

Before adapting it:

1. Build/package the native library for the device ABI.
2. Copy or extract it to the private path referenced by the sample.
3. Keep the attach path in a debug-only source set or build variant.
4. Pull `files/traceforge-events.jsonl` with your debuggable-app workflow.
5. Validate it with `traceforge validate`.

See [JVMTI setup](../docs/jvmti-setup.md) for constraints and options.

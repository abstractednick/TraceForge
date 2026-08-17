# Overview

TraceForge explores a small, inspectable path from Android/JVM runtime events to shareable trace
artifacts. It currently combines:

- a Kotlin runtime for explicit sections/markers and a pluggable agent attachment boundary;
- a C++ JVMTI agent for method and optional allocation events;
- a bounded asynchronous JSONL writer; and
- a Python 3.10+ CLI for validation, hotspot summaries, and Chrome Trace export.

The project deliberately keeps collection and analysis separate. The app writes a line-oriented
file that remains useful without a proprietary service, while analysis can happen off-device.

TraceForge is experimental. It does not replace Android Studio profilers, Perfetto, Macrobenchmark,
or production observability. Use it when a focused method-level event stream is useful and its
instrumentation cost is acceptable.

## Documentation

- [Architecture](architecture.md)
- [JVMTI setup](jvmti-setup.md)
- [Trace format](trace-format.md)
- [Best practices](best-practices.md)
- [Troubleshooting](troubleshooting.md)

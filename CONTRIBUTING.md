# Contributing to TraceForge

Thanks for helping improve TraceForge. The project is experimental; discuss large API or trace
format changes in an issue before investing in an implementation.

## Development setup

- JDK 17+
- Python 3.10+
- CMake 3.22+ and a C++17 compiler
- Android NDK/device tooling for Android agent testing

Run the checks relevant to your change:

```bash
python3 -m unittest discover -s traceforge-cli/tests -v
cmake -S traceforge-agent -B /tmp/traceforge-agent-build
cmake --build /tmp/traceforge-agent-build
```

If `gradlew` exists and is executable:

```bash
./gradlew build
```

## Pull requests

- Keep changes focused and add tests for behavior changes.
- Preserve JSONL backward compatibility or document a migration.
- Distinguish measured results from expectations; include device/build/options for benchmarks.
- Do not include user traces, secrets, APKs, or generated build output.
- Update documentation and `CHANGELOG.md` for user-visible changes.
- Follow the [code of conduct](CODE_OF_CONDUCT.md).

Native callback code must not allow C++ exceptions to cross the JVMTI C boundary. Avoid blocking
I/O in callbacks and document ownership/lifetime assumptions.

## Reporting bugs

Include the Android version, ABI, device or emulator, build type, attach method, sanitized agent
options, CLI/Python version, reproduction steps, and relevant `TraceForge` logs. Minimize trace
samples and remove sensitive identifiers before attaching them.

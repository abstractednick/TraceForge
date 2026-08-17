# TraceForge JVMTI agent

This module is an experimental Android JVMTI tracing agent. It records method
entry/exit and VM-observed object allocation events as JSON Lines. The callback
path copies event metadata into a bounded in-process queue; one native thread
writes batches to disk.

It is not claimed to be production-compatible across Android releases,
vendors, ART configurations, or non-debuggable applications. Treat it as a
diagnostic tool and validate it on every target OS/device combination.

## Build

An NDK toolchain with CMake 3.18 or newer is required. JVMTI is available on
Android 8.0/API 26 and newer, while convenient runtime attach mechanisms vary by
Android release.

```sh
cmake -S traceforge-agent -B build/traceforge-agent-arm64 \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DTRACEFORGE_BUILD_AGENT=ON -DTRACEFORGE_BUILD_TESTS=OFF
cmake --build build/traceforge-agent-arm64
```

The output is `libtraceforge-agent.so`. Package it in the app's matching ABI
directory or copy it to a location the debuggable target can load. Android's
SELinux and application sandbox rules apply to both the library and output
path. A path writable by the target app, such as one below its
`Context.getFilesDir()`, is usually appropriate.

Host tests intentionally compile without JNI/JVMTI headers:

```sh
cmake -S traceforge-agent -B build/traceforge-agent-host \
  -DTRACEFORGE_BUILD_AGENT=OFF -DTRACEFORGE_BUILD_TESTS=ON
cmake --build build/traceforge-agent-host
ctest --test-dir build/traceforge-agent-host --output-on-failure
```

## Attach and options

The exported `Agent_OnAttach` and `Agent_OnLoad` entry points share the same
initialization path. Runtime attach must be performed only through an Android
mechanism supported by the target release, for example `Debug.attachJvmtiAgent`
from inside a debuggable app. Do not assume desktop JVM `-agentpath` behavior is
available on an Android device.

Options are comma-separated `key=value` pairs:

- `output` (required): JSONL file path writable by the app process.
- `methods`: `true`/`false`; defaults to `true`.
- `allocations`: `true`/`false`; defaults to `false`.
- `include`: Java package prefix, in dotted or slash form. An empty value
  includes all classes.
- `max_events`: bounded queue capacity, from 64 to 1,000,000; defaults to 8192.

Values can be single- or double-quoted and backslash escapes the next
character. Unknown, duplicate, malformed, and out-of-range options fail attach.

Example:

```text
output=/data/user/0/com.example.app/files/trace.jsonl,methods=true,allocations=false,include=com.example.app,max_events=16384
```

The `include` filter is applied to class signatures in the callback. It reduces
writer traffic but does not prevent ART from delivering the enabled global
events to the agent.

## Output and shutdown

Each line is an independent JSON object with a `type` and monotonic
`timestamp_ns`. Timestamps are suitable for ordering and duration calculations,
not wall-clock correlation. Method records contain class, method, descriptor,
and best-effort thread name. Allocation records contain class and byte size.
The output file is truncated when the agent starts.

On unload, event notification is disabled before the global callback state is
released. In-flight callbacks retain state safely, the writer drains accepted
events, and a final diagnostic record reports the observed dropped-event count.
`Submit` never waits for disk; a full queue drops new events.

## Safety and limitations

- Method entry/exit tracing is global and extremely expensive. Always use a
  narrow `include` prefix and test overhead before collecting useful data.
- Allocation callbacks can substantially perturb allocation rate, GC timing,
  and application behavior. They are disabled by default.
- Capability availability is checked at attach time. Attach fails rather than
  silently collecting an incomplete requested event set.
- Thread names and JVMTI symbol lookup happen in callbacks and add overhead.
- No stack traces, object identities, return values, wall-clock timestamps,
  rotation, compression, or cross-process coordination are implemented.
- Disk-full and write errors stop useful output. `healthy()` exists in the
  portable writer, but this minimal agent does not expose live health telemetry.
- A native crash, process kill, or VM termination that does not invoke
  `Agent_OnUnload` can lose queued events.
- JSON strings preserve non-ASCII bytes supplied by JVMTI. ART normally returns
  modified UTF-8; strict Unicode normalization is not performed.
- This module has no privilege bypass. It cannot attach to non-debuggable apps
  or evade platform loading, SELinux, or filesystem policy.

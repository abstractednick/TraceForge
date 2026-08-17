# JVMTI setup

TraceForge's native agent targets Android's JVMTI support. Dynamic attachment requires Android 8.0
(API 26) or newer and a debuggable application. Device policy, platform changes, and OEM behavior
can impose additional restrictions.

## Build and package

Build `traceforge-agent/` with an Android NDK toolchain for every ABI you intend to run. The project
does not yet provide a complete Android packaging pipeline; your app must package or extract the
resulting `libtraceforge-agent.so` to a readable private path.

Attach from app code:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
) {
    Debug.attachJvmtiAgent(agentPath, options, classLoader)
}
```

Use this only from a debug/profile build. Keep the attach code out of release variants.

## Options

Options are comma-separated `key=value` pairs. Values can be single- or double-quoted, and a
backslash escapes the following character.

- `output` — destination JSONL path. Default: `/data/local/tmp/traceforge-events.jsonl`.
- `methods` — capture method entry/exit (`true` by default).
- `allocations` — capture VM object allocations (`false` by default).
- `include_package` — dot- or slash-separated package prefix.
- `max_events` — bounded queue capacity (`8192` by default).

Example:

```text
output=/data/user/0/com.example/files/trace.jsonl,methods=true,allocations=false,include_package=com.example,max_events=16384
```

Choose an output location writable by the app process. Pull private files with an appropriate
debug workflow such as `run-as`; do not assume `/data/local/tmp` is writable on every device.

## Verification

Check Logcat for the `TraceForge` tag. A successful trace begins with a `diagnostic` event and ends,
on orderly unload, with a diagnostic containing `dropped_events=N`.

# Troubleshooting

## Attachment fails

- Confirm the device is API 26+ and the app is debuggable.
- Verify the `.so` matches the process ABI and exists at the exact private path.
- Check Logcat with tag `TraceForge`; option and JVMTI capability errors are reported there.
- Ensure at least one of `methods` or `allocations` is enabled.

## The output file is absent or empty

- Select an app-writable path; `/data/local/tmp` is not universally writable by app UIDs.
- Keep the process alive long enough for attachment and events.
- Stop the app cleanly when possible so the queue is drained.
- Look for writer-open errors in Logcat.

## Too many events or severe slowdown

- Narrow `include_package`.
- Disable `allocations`.
- Trace a shorter scenario.
- Method entry/exit tracing is instrumentation, not sampling; broad filters can be expensive.

## Summary shows unmatched entries or exits

The bounded queue may have dropped one side of a method pair, attachment may have occurred while
methods were active, or the process may have ended without orderly shutdown. Check the final
`dropped_events` diagnostic and avoid relying on affected hotspot totals.

## CLI rejects a trace

Run `traceforge validate FILE` for line-specific errors. Each non-empty line must be an independent
JSON object. Use `--strict` only when unknown event types should fail validation. CLI exit codes are
0 (success), 1 (invalid trace), and 2 (usage or I/O failure).

## Chrome/Perfetto shows odd nesting

Chrome duration events assume balanced, properly nested method callbacks per thread. Dropped events
cannot be reconstructed reliably; collect a narrower trace and confirm zero drops.

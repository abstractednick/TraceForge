# Architecture

## Native collection path

1. A debuggable Android process loads the native library with `Debug.attachJvmtiAgent`.
2. `Agent_OnAttach` parses options and requests only the enabled JVMTI capabilities.
3. Method entry/exit and optional allocation callbacks resolve event metadata.
4. Callbacks submit events to a bounded in-memory queue and return; a full queue drops the event.
5. One writer thread serializes events as JSON Lines and flushes each batch.
6. Agent unload disables callbacks, records a dropped-event diagnostic, and drains the writer.

The queue prevents filesystem stalls from directly blocking callback threads, but metadata lookup
and queue synchronization still add overhead to every captured event.

## Kotlin runtime path

`TraceForge.start` creates a bounded recorder and accepts an optional `AgentAttacher`. Calls to
`traceSection` and `mark` produce runtime JSONL directly. The default attacher is a no-op; no
Android bridge to the bundled native agent is included yet. The runtime supports drop-oldest,
drop-latest, or explicitly blocking overflow behavior.

## Analysis path

The CLI validates each line independently, normalizes supported legacy field aliases, and can:

- count event types, threads, allocation bytes, and unmatched method boundaries;
- rank methods by inclusive elapsed time derived from paired entry/exit timestamps; and
- map events to Chrome Trace Event JSON for Perfetto or Chrome tracing viewers.

Method pairing is per thread and assumes properly nested callbacks. Queue loss can make boundaries
unmatched and totals incomplete. A summary is diagnostic evidence, not a CPU profile.

## Failure boundaries

- Invalid agent options fail attachment rather than silently changing behavior.
- Unsupported requested capabilities fail initialization.
- Writer open/thread failures fail initialization.
- A full queue or failed writer drops subsequent events; the app is kept running.
- CLI parse and schema errors identify source line numbers and produce a non-zero exit status.

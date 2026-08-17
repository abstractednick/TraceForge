# Trace format

TraceForge writes UTF-8 JSON Lines: each non-empty line is one complete event object. Timestamps are
monotonic nanoseconds in the traced process and have no defined relationship to wall-clock time.

## Native-agent events

| Field | Type | Meaning |
|---|---|---|
| `type` | string | `method_entry`, `method_exit`, `allocation`, or `diagnostic` |
| `timestamp_ns` | non-negative integer | Monotonic event timestamp |
| `thread` | string | Thread name when resolution succeeds |

Method events add `class`, `method`, and `signature`. Allocation events add `object_class` and
non-negative `size_bytes`. Diagnostic events add `message`; they may omit `thread`.

```json
{"type":"method_entry","timestamp_ns":1010000,"thread":"main","class":"Lcom/example/Checkout;","method":"submit","signature":"()V"}
{"type":"method_exit","timestamp_ns":1110000,"thread":"main","class":"Lcom/example/Checkout;","method":"submit","signature":"()V"}
{"type":"allocation","timestamp_ns":1120000,"thread":"main","object_class":"Ljava/lang/String;","size_bytes":64}
{"type":"diagnostic","timestamp_ns":1200000,"message":"TraceForge agent stopping; dropped_events=0"}
```

JVMTI class and method signatures use descriptor syntax, so names may look like
`Lcom/example/Checkout;` and `(Ljava/lang/String;)V`.

## Compatibility

The CLI accepts the design-stage aliases `methodEntry`, `methodExit`, `timeNs`, `threadId`, and
`sizeBytes`. New producers should emit the snake_case native format above.

Unknown event types generate warnings by default so newer traces remain inspectable. Use
`traceforge validate --strict` to reject them.

## Kotlin-runtime events

The Kotlin runtime emits explicit `section` and `marker` records. Both use `timestampNanos`,
`threadId`, `threadName`, and string-valued `attributes`. A section also has positive `id`, optional
`parentId`, `name`, `durationNanos`, and `failed`.

```json
{"type":"section","id":1,"name":"checkout","timestampNanos":1010000,"durationNanos":100000,"threadId":1,"threadName":"main","failed":false,"attributes":{"variant":"debug"}}
{"type":"marker","name":"checkout_complete","timestampNanos":1120000,"threadId":1,"threadName":"main","attributes":{}}
```

The CLI accepts and combines both producer schemas. A runtime section becomes a complete (`X`)
Chrome Trace event and contributes a `section:<name>` row to hotspot summaries.

## Chrome Trace conversion

Method entries/exits become `B`/`E` duration events. Allocations and diagnostics become thread-scope
instant events. Nanoseconds are converted to microseconds as required by the Chrome Trace Event
format. Conversion does not repair dropped or unmatched method boundaries.

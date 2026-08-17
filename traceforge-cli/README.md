# TraceForge CLI

A zero-runtime-dependency Python 3.10+ tool for TraceForge JSONL traces.

```bash
python -m pip install -e ./traceforge-cli
traceforge validate trace.jsonl
traceforge summary trace.jsonl --top 5
traceforge chrome trace.jsonl -o trace.chrome.json
```

Use `-` as an input or output path to stream through standard input/output. `validate --strict`
treats unknown event types as errors. Exit codes are `0` for success, `1` for an invalid trace,
and `2` for command-line or I/O errors.

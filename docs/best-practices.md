# Best practices

## Keep tracing scoped

- Use a debuggable or dedicated profiling build.
- Set `include_package` to the smallest useful application namespace.
- Record short, repeatable scenarios and include warm-up runs.
- Leave allocations disabled until method traces indicate that allocation detail is needed.

## Interpret carefully

JVMTI callbacks change the program being measured. Method callbacks can be invoked at very high
frequency; allocation callbacks add further work. Compare several traces and confirm suspected
hotspots with Android Studio, Perfetto, or a benchmark before making performance claims.

The CLI reports inclusive elapsed time between method entry and exit. It does not distinguish CPU
execution from scheduling, blocking, or sleep. Lost events produce unmatched boundaries and can
distort totals.

## Control output and privacy

- Use a private, writable output path with sufficient free space.
- Monitor `dropped_events` in the final diagnostic.
- Avoid scenario marker and thread names containing secrets or user data.
- Treat traces as potentially sensitive source-level metadata.
- Delete traces from test devices and CI artifacts according to your retention policy.

## Compare responsibly

Keep device model, Android version, build variant, thermal state, scenario, and TraceForge options
constant. Do not compare traced and untraced timings as if instrumentation overhead were zero.

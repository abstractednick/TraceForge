# Security policy

## Supported versions

TraceForge has not published a stable release. Security fixes are made on the default branch; no
older version line is currently supported.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting feature for this repository when available.
If it is not enabled, contact the repository maintainers privately rather than opening a public
issue. Do not include secrets, personal data, proprietary traces, or weaponized proof-of-concept
material in a public report.

Include affected revision, impact, prerequisites, reproduction steps, and a minimal sanitized
example. Maintainers will acknowledge reports when capacity permits; this project does not promise
a specific response or remediation deadline.

## Trace data

Trace files can disclose package, class, method, signature, thread, object-class, path, and marker
information. Store them as sensitive development artifacts, use app-private output locations,
restrict CI artifact access, sanitize before sharing, and delete them according to your retention
policy. TraceForge does not currently encrypt or redact output.

Only attach native agents to apps and devices you are authorized to inspect.

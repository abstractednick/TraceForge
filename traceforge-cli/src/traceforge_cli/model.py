"""Trace loading and validation."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, TextIO

TYPE_ALIASES = {
    "method_entry": "methodEntry",
    "method_exit": "methodExit",
    "methodEntry": "methodEntry",
    "methodExit": "methodExit",
}
METHOD_TYPES = {"methodEntry", "methodExit"}
KNOWN_TYPES = METHOD_TYPES | {
    "allocation", "diagnostic", "section", "gcStart", "gcFinish", "threadStart", "threadEnd", "marker"
}


@dataclass(frozen=True)
class Issue:
    line: int
    message: str
    severity: str = "error"

    def __str__(self) -> str:
        return f"line {self.line}: {self.severity}: {self.message}"


@dataclass
class Trace:
    events: list[dict[str, Any]]
    issues: list[Issue]

    @property
    def errors(self) -> list[Issue]:
        return [issue for issue in self.issues if issue.severity == "error"]


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def validate_event(event: object, line: int, *, strict: bool = False) -> list[Issue]:
    if not isinstance(event, dict):
        return [Issue(line, "event must be a JSON object")]

    issues: list[Issue] = []
    event_type = event.get("type")
    normalized_type = TYPE_ALIASES.get(event_type, event_type) if isinstance(event_type, str) else event_type
    if not isinstance(event_type, str) or not event_type:
        issues.append(Issue(line, "'type' must be a non-empty string"))
    elif normalized_type not in KNOWN_TYPES:
        issues.append(Issue(line, f"unknown event type {event_type!r}", "error" if strict else "warning"))

    timestamp = event.get("timeNs", event.get("timestamp_ns", event.get("timestampNanos")))
    if not _is_int(timestamp):
        issues.append(Issue(line, "'timestamp_ns' (or 'timeNs') must be an integer"))
    elif timestamp < 0:
        issues.append(Issue(line, "timestamp must be non-negative"))

    if normalized_type in METHOD_TYPES:
        thread = event.get("threadId", event.get("thread"))
        if not isinstance(thread, (str, int)) or isinstance(thread, bool) or thread == "":
            issues.append(Issue(line, "'thread' (or 'threadId') is required for method events"))
        for field in ("class", "method"):
            if not isinstance(event.get(field), str) or not event[field]:
                issues.append(Issue(line, f"'{field}' must be a non-empty string for {event_type}"))
    if normalized_type == "methodExit" and "durationNs" in event:
        if not _is_int(event["durationNs"]) or event["durationNs"] < 0:
            issues.append(Issue(line, "'durationNs' must be a non-negative integer"))
    if normalized_type == "allocation":
        size = event.get("sizeBytes", event.get("size_bytes"))
        if not _is_int(size) or size < 0:
            issues.append(Issue(line, "'size_bytes' (or 'sizeBytes') must be a non-negative integer"))
    if normalized_type in {"section", "marker"}:
        if not isinstance(event.get("name"), str) or not event["name"]:
            issues.append(Issue(line, f"'name' must be a non-empty string for {normalized_type}"))
    if normalized_type == "section":
        duration = event.get("durationNs", event.get("durationNanos"))
        if not _is_int(duration) or duration < 0:
            issues.append(Issue(line, "'durationNanos' (or 'durationNs') must be non-negative"))
    return issues


def _normalize(event: dict[str, Any], line: int) -> dict[str, Any]:
    normalized = dict(event)
    normalized["type"] = TYPE_ALIASES.get(event["type"], event["type"])
    normalized["timeNs"] = event.get("timeNs", event.get("timestamp_ns", event.get("timestampNanos")))
    normalized["threadId"] = event.get("threadId", event.get("thread", "global"))
    if "size_bytes" in event and "sizeBytes" not in event:
        normalized["sizeBytes"] = event["size_bytes"]
    if "durationNanos" in event and "durationNs" not in event:
        normalized["durationNs"] = event["durationNanos"]
    normalized["_line"] = line
    return normalized


def load_stream(stream: TextIO, *, strict: bool = False) -> Trace:
    events: list[dict[str, Any]] = []
    issues: list[Issue] = []
    previous_time: int | None = None

    for line_number, raw_line in enumerate(stream, 1):
        if not raw_line.strip():
            continue
        try:
            event = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            issues.append(Issue(line_number, f"invalid JSON at column {exc.colno}: {exc.msg}"))
            continue
        event_issues = validate_event(event, line_number, strict=strict)
        issues.extend(event_issues)
        if isinstance(event, dict) and not any(issue.severity == "error" for issue in event_issues):
            normalized = _normalize(event, line_number)
            events.append(normalized)
            time_ns = normalized["timeNs"]
            if previous_time is not None and time_ns < previous_time:
                issues.append(Issue(line_number, "timestamp is earlier than the previous event", "warning"))
            previous_time = time_ns
    return Trace(events, issues)


def load_path(path: Path, *, strict: bool = False) -> Trace:
    with path.open("r", encoding="utf-8") as stream:
        return load_stream(stream, strict=strict)


def clean_events(events: Iterable[dict[str, Any]]) -> Iterable[dict[str, Any]]:
    for event in events:
        yield {key: value for key, value in event.items() if key != "_line"}

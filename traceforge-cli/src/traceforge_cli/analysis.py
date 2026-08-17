"""Trace aggregation and Chrome Trace conversion."""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Any


@dataclass
class MethodStats:
    calls: int = 0
    total_ns: int = 0
    max_ns: int = 0


def analyze(events: list[dict[str, Any]]) -> dict[str, Any]:
    counts = Counter(event["type"] for event in events)
    stacks: dict[int, list[dict[str, Any]]] = defaultdict(list)
    methods: dict[str, MethodStats] = defaultdict(MethodStats)
    unmatched_exits = 0
    allocation_bytes = 0

    for event in events:
        event_type = event["type"]
        thread_id = event["threadId"]
        if event_type == "methodEntry":
            stacks[thread_id].append(event)
        elif event_type == "methodExit":
            key = f"{event.get('class', '?')}.{event.get('method', '?')}"
            duration = event.get("durationNs")
            if stacks[thread_id]:
                entry = stacks[thread_id].pop()
                if duration is None:
                    duration = max(0, event["timeNs"] - entry["timeNs"])
                entry_key = f"{entry.get('class', '?')}.{entry.get('method', '?')}"
                key = entry_key
            else:
                unmatched_exits += 1
            if isinstance(duration, int):
                stats = methods[key]
                stats.calls += 1
                stats.total_ns += duration
                stats.max_ns = max(stats.max_ns, duration)
        elif event_type == "allocation":
            allocation_bytes += event["sizeBytes"]
        elif event_type == "section":
            stats = methods[f"section:{event['name']}"]
            duration = event["durationNs"]
            stats.calls += 1
            stats.total_ns += duration
            stats.max_ns = max(stats.max_ns, duration)

    times = [event["timeNs"] for event in events]
    hotspots = [
        {"method": name, "calls": stats.calls, "totalNs": stats.total_ns, "maxNs": stats.max_ns}
        for name, stats in sorted(methods.items(), key=lambda item: (-item[1].total_ns, item[0]))
    ]
    return {
        "events": len(events),
        "eventTypes": dict(sorted(counts.items())),
        "threads": len({event["threadId"] for event in events}),
        "spanNs": max(times) - min(times) if times else 0,
        "allocationBytes": allocation_bytes,
        "unmatchedEntries": sum(len(stack) for stack in stacks.values()),
        "unmatchedExits": unmatched_exits,
        "hotspots": hotspots,
    }


def to_chrome_trace(events: list[dict[str, Any]]) -> dict[str, Any]:
    output: list[dict[str, Any]] = []
    thread_ids: dict[str, int] = {}
    for event in events:
        event_type = event["type"]
        name = (
            f"{event.get('class', '')}.{event.get('method', '')}".strip(".")
            or str(event.get("name", event_type))
        )
        raw_thread = event["threadId"]
        if isinstance(raw_thread, int):
            thread_id = raw_thread
        else:
            thread_id = thread_ids.setdefault(str(raw_thread), len(thread_ids) + 1)
        converted: dict[str, Any] = {
            "name": name,
            "cat": "traceforge",
            "pid": 1,
            "tid": thread_id,
            "ts": event["timeNs"] / 1_000,
        }
        if event_type == "methodEntry":
            converted["ph"] = "B"
        elif event_type == "methodExit":
            converted["ph"] = "E"
        elif event_type == "section":
            converted.update({"ph": "X", "dur": event["durationNs"] / 1_000})
        else:
            converted.update({"ph": "i", "s": "t"})
        args = {
            key: value
            for key, value in event.items()
            if key not in {
                "_line", "type", "timeNs", "timestamp_ns", "threadId", "thread",
                "class", "method", "name", "size_bytes", "durationNanos", "durationNs",
            }
        }
        if args:
            converted["args"] = args
        output.append(converted)
    return {"traceEvents": output, "displayTimeUnit": "ns"}

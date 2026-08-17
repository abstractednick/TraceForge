"""Command-line interface for TraceForge traces."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Sequence

from . import __version__
from .analysis import analyze, to_chrome_trace
from .model import Trace, load_path, load_stream


class CliError(Exception):
    """An expected user-facing failure."""


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="traceforge", description="Inspect TraceForge JSONL traces.")
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate", help="validate a JSONL trace")
    validate.add_argument("input", help="trace path, or - for standard input")
    validate.add_argument("--strict", action="store_true", help="treat unknown event types as errors")

    summary = subparsers.add_parser("summary", aliases=["analyze"], help="summarize events and hotspots")
    summary.add_argument("input", help="trace path, or - for standard input")
    summary.add_argument("--top", type=int, default=10, help="number of hotspots to print (default: 10)")
    summary.add_argument("--json", action="store_true", help="emit machine-readable JSON")

    chrome = subparsers.add_parser("chrome", help="export Chrome Trace Event JSON")
    chrome.add_argument("input", help="trace path, or - for standard input")
    chrome.add_argument("-o", "--output", required=True, help="output path, or - for standard output")
    return parser


def _load(path_text: str, *, strict: bool = False) -> Trace:
    if path_text == "-":
        return load_stream(sys.stdin, strict=strict)
    path = Path(path_text)
    try:
        return load_path(path, strict=strict)
    except OSError as exc:
        raise CliError(f"cannot read {path}: {exc.strerror or exc}") from exc


def _require_valid(trace: Trace) -> None:
    if trace.errors:
        for issue in trace.issues:
            print(issue, file=sys.stderr)
        raise CliError(f"trace contains {len(trace.errors)} error(s)")


def _format_duration(nanoseconds: int) -> str:
    if nanoseconds >= 1_000_000_000:
        return f"{nanoseconds / 1_000_000_000:.3f} s"
    if nanoseconds >= 1_000_000:
        return f"{nanoseconds / 1_000_000:.3f} ms"
    if nanoseconds >= 1_000:
        return f"{nanoseconds / 1_000:.3f} µs"
    return f"{nanoseconds} ns"


def _print_summary(result: dict[str, Any], top: int) -> None:
    print(f"Events:      {result['events']}")
    print(f"Threads:     {result['threads']}")
    print(f"Time span:   {_format_duration(result['spanNs'])}")
    print(f"Allocations: {result['allocationBytes']} bytes")
    print(
        f"Unmatched:   {result['unmatchedEntries']} entries, "
        f"{result['unmatchedExits']} exits"
    )
    print("Event types: " + ", ".join(f"{key}={value}" for key, value in result["eventTypes"].items()))
    if result["hotspots"] and top:
        print("\nHotspots (inclusive):")
        for index, hotspot in enumerate(result["hotspots"][:top], 1):
            print(
                f"{index:>2}. {_format_duration(hotspot['totalNs']):>12}  "
                f"{hotspot['calls']:>5} calls  {hotspot['method']}"
            )


def _write_json(payload: object, output: str) -> None:
    text = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
    if output == "-":
        sys.stdout.write(text)
        return
    path = Path(output)
    try:
        path.write_text(text, encoding="utf-8")
    except OSError as exc:
        raise CliError(f"cannot write {path}: {exc.strerror or exc}") from exc


def run(args: argparse.Namespace) -> int:
    trace = _load(args.input, strict=getattr(args, "strict", False))
    if args.command == "validate":
        for issue in trace.issues:
            print(issue, file=sys.stderr)
        if trace.errors:
            print(f"INVALID: {len(trace.errors)} error(s), {len(trace.events)} valid event(s)")
            return 1
        warnings = len(trace.issues)
        print(f"OK: {len(trace.events)} event(s), {warnings} warning(s)")
        return 0

    _require_valid(trace)
    result = analyze(trace.events)
    if args.command in {"summary", "analyze"}:
        if args.top < 0:
            raise CliError("--top must be non-negative")
        if args.json:
            print(json.dumps(result, indent=2, ensure_ascii=False))
        else:
            _print_summary(result, args.top)
    elif args.command == "chrome":
        _write_json(to_chrome_trace(trace.events), args.output)
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    try:
        return run(parser.parse_args(argv))
    except CliError as exc:
        print(f"traceforge: error: {exc}", file=sys.stderr)
        return 2
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

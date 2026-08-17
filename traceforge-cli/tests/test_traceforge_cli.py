from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from traceforge_cli.analysis import analyze, to_chrome_trace
from traceforge_cli.cli import main
from traceforge_cli.model import load_path, load_stream

FIXTURES = Path(__file__).parent / "fixtures"


class ModelTests(unittest.TestCase):
    def test_valid_fixture(self) -> None:
        trace = load_path(FIXTURES / "valid.jsonl")
        self.assertEqual([], trace.errors)
        self.assertEqual(4, len(trace.events))

    def test_invalid_fixture_reports_lines(self) -> None:
        trace = load_path(FIXTURES / "invalid.jsonl")
        self.assertEqual([2, 3, 4], [issue.line for issue in trace.errors])

    def test_unknown_event_is_warning_or_strict_error(self) -> None:
        data = '{"type":"futureEvent","timeNs":1,"threadId":2}\n'
        self.assertEqual([], load_stream(io.StringIO(data)).errors)
        self.assertEqual(1, len(load_stream(io.StringIO(data), strict=True).errors))

    def test_blank_lines_are_ignored(self) -> None:
        trace = load_stream(io.StringIO("\n  \n"))
        self.assertEqual([], trace.events)
        self.assertEqual([], trace.issues)

    def test_runtime_section_schema(self) -> None:
        data = (
            '{"type":"section","id":1,"name":"checkout","timestampNanos":1000,'
            '"durationNanos":250,"threadId":1,"threadName":"main","failed":false,'
            '"attributes":{}}\n'
        )
        trace = load_stream(io.StringIO(data))
        self.assertEqual([], trace.errors)
        self.assertEqual(250, trace.events[0]["durationNs"])


class AnalysisTests(unittest.TestCase):
    def setUp(self) -> None:
        self.events = load_path(FIXTURES / "valid.jsonl").events

    def test_analysis_aggregates_methods_and_allocations(self) -> None:
        result = analyze(self.events)
        self.assertEqual(4, result["events"])
        self.assertEqual(64, result["allocationBytes"])
        self.assertEqual(100_000, result["hotspots"][0]["totalNs"])
        self.assertEqual(0, result["unmatchedEntries"])

    def test_chrome_trace_uses_microseconds(self) -> None:
        result = to_chrome_trace(self.events)
        self.assertEqual(1_010.0, result["traceEvents"][1]["ts"])
        self.assertEqual("B", result["traceEvents"][1]["ph"])
        self.assertEqual("E", result["traceEvents"][3]["ph"])

    def test_runtime_section_becomes_complete_event(self) -> None:
        event = load_stream(
            io.StringIO(
                '{"type":"section","id":1,"name":"checkout","timestampNanos":1000,'
                '"durationNanos":250,"threadId":1,"threadName":"main","attributes":{}}\n'
            )
        ).events[0]
        result = to_chrome_trace([event])["traceEvents"][0]
        self.assertEqual("X", result["ph"])
        self.assertEqual(0.25, result["dur"])


class CliTests(unittest.TestCase):
    def test_validate_exit_codes(self) -> None:
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(0, main(["validate", str(FIXTURES / "valid.jsonl")]))
            self.assertEqual(1, main(["validate", str(FIXTURES / "invalid.jsonl")]))

    def test_missing_input_is_io_error(self) -> None:
        with redirect_stderr(io.StringIO()):
            self.assertEqual(2, main(["validate", "does-not-exist.jsonl"]))

    def test_summary_json(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(0, main(["summary", str(FIXTURES / "valid.jsonl"), "--json"]))
        self.assertEqual(4, json.loads(output.getvalue())["events"])

    def test_chrome_writes_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "trace.json"
            self.assertEqual(0, main(["chrome", str(FIXTURES / "valid.jsonl"), "-o", str(output)]))
            self.assertEqual(4, len(json.loads(output.read_text())["traceEvents"]))


if __name__ == "__main__":
    unittest.main()

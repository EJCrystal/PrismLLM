#!/usr/bin/env python3
"""Record CI command durations and summarize Maven reports without test output."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PHASES = {"compile", "upstream", "app", "extensions"}


def record_command(root: Path, phase: str, command: list[str]) -> int:
    if phase not in PHASES or not command:
        raise ValueError("A known phase and a nonempty command are required")
    timings_path = root / "target/ci-test-timings.json"
    timings = json.loads(timings_path.read_text(encoding="utf-8")) if timings_path.exists() else []
    started = time.perf_counter()
    try:
        return_code = subprocess.run(command, cwd=root, check=False).returncode
    except OSError as error:
        print(f"::error::Unable to start {phase}: {error}", file=sys.stderr)
        return_code = 127
    timings.append({
        "phase": phase,
        "seconds": round(time.perf_counter() - started, 3),
        "exit_code": return_code,
    })
    timings_path.parent.mkdir(parents=True, exist_ok=True)
    timings_path.write_text(json.dumps(timings, indent=2) + "\n", encoding="utf-8")
    return return_code if return_code >= 0 else 128 - return_code


def report_paths(root: Path) -> list[Path]:
    return sorted({
        *root.glob("prism-*/target/surefire-reports/TEST-*.xml"),
        *root.glob("extensions/*/target/surefire-reports/TEST-*.xml"),
        *root.glob("extensions/*/target/failsafe-reports/TEST-*.xml"),
    })


def collect_reports(root: Path) -> list[dict]:
    modules = []
    by_module = {}
    for path in report_paths(root):
        module = path.parent.parent.parent.relative_to(root).as_posix()
        result = by_module.setdefault(module, {
            "module": module, "suites": 0, "tests": 0, "failures": 0,
            "errors": 0, "skipped": 0, "class_seconds": 0.0, "cases": [],
        })
        document = ET.parse(path).getroot()
        # A testsuites wrapper can repeat aggregate totals; count only leaf suites.
        for suite in document.iter("testsuite"):
            if suite.findall(".//testsuite"):
                continue
            result["suites"] += 1
            for key in ("tests", "failures", "errors", "skipped"):
                result[key] += int(suite.get(key, "0"))
            result["class_seconds"] += float(suite.get("time", "0"))
            for case in suite.findall("testcase"):
                status = next((name for name in ("failure", "error", "skipped") if case.find(name) is not None), "passed")
                result["cases"].append({
                    "class": case.get("classname", suite.get("name", "")),
                    "name": case.get("name", ""),
                    "status": status,
                })
    for module in sorted(by_module):
        result = by_module[module]
        result["class_seconds"] = round(result["class_seconds"], 3)
        result["cases"].sort(key=lambda case: (case["class"], case["name"]))
        modules.append(result)
    return modules


def preserve_sanitized_reports(root: Path) -> None:
    paths = report_paths(root)
    expected = {root / "target/ci-sanitized-reports" / path.relative_to(root) for path in paths}
    for stale in (root / "target/ci-sanitized-reports").rglob("TEST-*.xml"):
        if stale not in expected:
            stale.unlink()
    for source in paths:
        document = ET.parse(source).getroot()
        output = ET.Element("testsuites")
        for suite in document.iter("testsuite"):
            if suite.findall(".//testsuite"):
                continue
            sanitized_suite = ET.SubElement(output, "testsuite", {
                key: value for key, value in suite.attrib.items()
                if key in {"name", "tests", "failures", "errors", "skipped", "time"}
            })
            for case in suite.findall("testcase"):
                sanitized_case = ET.SubElement(sanitized_suite, "testcase", {
                    key: value for key, value in case.attrib.items() if key in {"classname", "name", "time"}
                })
                for status in ("failure", "error", "skipped"):
                    if case.find(status) is not None:
                        ET.SubElement(sanitized_case, status)
        destination = root / "target/ci-sanitized-reports" / source.relative_to(root)
        destination.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(output).write(destination, encoding="utf-8", xml_declaration=True)


def render_summary(timings: list[dict], modules: list[dict]) -> str:
    lines = ["## Backend test measurements", "", "### Commands", "", "| Phase | Seconds | Exit code |", "| --- | ---: | ---: |"]
    for timing in timings:
        if timing["phase"] not in PHASES:
            raise ValueError("Unknown timing phase")
        lines.append(f"| {timing['phase']} | {float(timing['seconds']):.3f} | {int(timing['exit_code'])} |")
    if not timings:
        lines.append("| Not executed | — | — |")
    lines.extend([
        "", "### Executed test reports", "",
        "| Module | Suites | Tests | Failures | Errors | Skipped | Class seconds |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ])
    for module in modules:
        lines.append(
            f"| {module['module']} | {module['suites']} | {module['tests']} | {module['failures']} "
            f"| {module['errors']} | {module['skipped']} | {module['class_seconds']:.3f} |"
        )
    if not modules:
        lines.append("| No test reports produced | — | — | — | — | — | — |")
    lines.extend([
        "", "Class seconds sum reported suite durations, not command wall time.",
        "A filtered run reports only selected tests; zero skipped does not mean full coverage.",
        "Reports and timings are uploaded even on failure. No test output or request bodies are included in this summary.",
    ])
    return "\n".join(lines) + "\n"


def summarize(root: Path) -> None:
    timing_path = root / "target/ci-test-timings.json"
    timings = json.loads(timing_path.read_text(encoding="utf-8")) if timing_path.exists() else []
    modules = collect_reports(root)
    preserve_sanitized_reports(root)
    report = {"commit": os.environ.get("GITHUB_SHA", ""), "timings": timings, "modules": modules}
    report_path = root / "target/ci-test-report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = render_summary(timings, modules)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write(summary)
    print(summary, end="")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    actions = parser.add_subparsers(dest="action", required=True)
    run = actions.add_parser("run")
    run.add_argument("phase", choices=sorted(PHASES))
    run.add_argument("command", nargs=argparse.REMAINDER)
    actions.add_parser("summary")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.action == "run":
        command = args.command[1:] if args.command[:1] == ["--"] else args.command
        return record_command(root, args.phase, command)
    summarize(root)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)

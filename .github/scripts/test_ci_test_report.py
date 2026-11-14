"""CI timing and report regressions; run with python3 .github/scripts/test_ci_test_report.py."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).parent))

from ci_test_report import collect_reports, preserve_sanitized_reports, record_command, render_summary

SCRIPT = Path(__file__).with_name("ci_test_report.py")


class TestReportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def report(self, module, xml, name="sample", kind="surefire"):
        path = self.root / module / "target" / f"{kind}-reports" / f"TEST-{name}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(xml, encoding="utf-8")
        return path

    def test_commands_append_timings_and_preserve_exit_status(self):
        for phase, status in (("compile", 0), ("app", 3)):
            self.assertEqual(record_command(self.root, phase, [sys.executable, "-I", "-c", f"raise SystemExit({status})"]), status)
        timings = json.loads((self.root / "target/ci-test-timings.json").read_text())
        self.assertEqual([item["phase"] for item in timings], ["compile", "app"])
        self.assertEqual([item["exit_code"] for item in timings], [0, 3])
        self.assertTrue(all(item["seconds"] >= 0 for item in timings))

    def test_cli_does_not_evaluate_shell_fragments(self):
        marker = self.root / "must-not-exist"
        result = subprocess.run(
            [sys.executable, "-I", "-B", str(SCRIPT), "--root", str(self.root), "run", "app", "--",
             sys.executable, "-I", "-c", "import sys; assert sys.argv[1].startswith('; touch')", f"; touch {marker}"],
            capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(marker.exists())

    def test_missing_executable_fails_and_records_measurement(self):
        self.assertEqual(record_command(self.root, "app", [str(self.root / "no-command")]), 127)
        timing = json.loads((self.root / "target/ci-test-timings.json").read_text())[0]
        self.assertEqual(timing["exit_code"], 127)

    def test_aggregate_reports_count_once_and_omit_output(self):
        self.report("prism-app", '''<testsuites tests="99"><testsuite name="ExampleTest" tests="4" failures="1" errors="1" skipped="1" time="2.25">
          <testcase classname="ExampleTest" name="passes"/>
          <testcase classname="ExampleTest" name="fails"><failure message="private failure text"/></testcase>
          <testcase classname="ExampleTest" name="errors"><error>private stack</error></testcase>
          <testcase classname="ExampleTest" name="skips"><skipped/></testcase>
          <system-out>private request body</system-out></testsuite></testsuites>''')
        self.report("extensions/quota-providers", '<testsuite name="ExtensionTest" tests="1" failures="0" errors="0" skipped="0" time="0.5"><testcase name="works"/></testsuite>')
        self.report("extensions/quota-providers", '<testsuite name="ExtensionIT" tests="1" time="0.25"><testcase name="integration"/></testsuite>', name="integration", kind="failsafe")
        modules = collect_reports(self.root)
        app = next(item for item in modules if item["module"] == "prism-app")
        self.assertEqual((app["suites"], app["tests"], app["failures"], app["errors"], app["skipped"]), (1, 4, 1, 1, 1))
        self.assertEqual({case["status"] for case in app["cases"]}, {"passed", "failure", "error", "skipped"})
        extension = next(item for item in modules if item["module"] == "extensions/quota-providers")
        self.assertEqual((extension["tests"], extension["suites"], extension["class_seconds"]), (2, 2, 0.75))
        self.assertNotIn("private", json.dumps(modules))
        self.assertNotIn("private", render_summary([], modules))

    def test_sanitized_artifacts_keep_status_and_time_without_properties_or_bodies(self):
        source = self.report("prism-app", '''<testsuite name="ExampleTest" tests="2" failures="1" errors="0" skipped="1" time="1.5">
          <properties><property name="private" value="private environment"/></properties>
          <testcase classname="ExampleTest" name="fails" time="1.25"><failure message="private response" type="private class">private stack</failure><system-out>private request</system-out></testcase>
          <testcase classname="ExampleTest" name="skips" time="0.25"><skipped message="private reason"/></testcase>
          <system-err>private stderr</system-err></testsuite>''')
        preserve_sanitized_reports(self.root)
        artifact = self.root / "target/ci-sanitized-reports" / source.relative_to(self.root)
        sanitized = artifact.read_text()
        self.assertNotIn("private", sanitized)
        suite = ET.fromstring(sanitized).find("testsuite")
        self.assertEqual(suite.get("tests"), "2")
        cases = suite.findall("testcase")
        self.assertEqual(cases[0].get("time"), "1.25")
        self.assertIsNotNone(cases[0].find("failure"))
        self.assertIsNotNone(cases[1].find("skipped"))
        self.assertIn("private", source.read_text())

    def test_sanitized_artifacts_remove_reports_no_longer_present(self):
        source = self.report("prism-app", '<testsuite name="OldTest" tests="1"><testcase name="old"/></testsuite>')
        preserve_sanitized_reports(self.root)
        artifact = self.root / "target/ci-sanitized-reports" / source.relative_to(self.root)
        self.assertTrue(artifact.exists())
        source.unlink()
        preserve_sanitized_reports(self.root)
        self.assertFalse(artifact.exists())

    def test_malformed_report_is_not_silently_ignored(self):
        self.report("prism-app", "<invalid")
        with self.assertRaises(ET.ParseError):
            collect_reports(self.root)

    def test_no_reports_are_reported_as_not_executed(self):
        summary = render_summary([], collect_reports(self.root))
        self.assertIn("No test reports produced", summary)
        self.assertIn("Not executed", summary)
        self.assertIn("zero skipped does not mean full coverage", summary)

    def test_unknown_phase_or_empty_command_is_rejected(self):
        for phase, command in (("arbitrary", ["true"]), ("app", [])):
            with self.assertRaises(ValueError):
                record_command(self.root, phase, command)

    def test_summary_appends_to_github_and_writes_identity_report(self):
        self.report("prism-quota", '<testsuite name="QuotaTest" tests="1" time="0.1"><testcase classname="QuotaTest" name="counts"/></testsuite>')
        summary = self.root / "summary.md"
        summary.write_text("existing\n")
        result = subprocess.run(
            [sys.executable, "-I", "-B", str(SCRIPT), "--root", str(self.root), "summary"],
            env={**os.environ, "GITHUB_STEP_SUMMARY": str(summary), "GITHUB_SHA": "test-commit"},
            capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(summary.read_text().startswith("existing\n## Backend"))
        report = json.loads((self.root / "target/ci-test-report.json").read_text())
        self.assertEqual(report["commit"], "test-commit")
        self.assertEqual(report["modules"][0]["cases"], [{"class": "QuotaTest", "name": "counts", "status": "passed"}])


if __name__ == "__main__":
    unittest.main(verbosity=2)

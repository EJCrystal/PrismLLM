"""CI module-selection regressions; run with python3 .github/scripts/test_ci_test_modules.py."""

import itertools
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).parent))

from ci_test_modules import CHANGE_FLAGS, changes_from_env, resolve_modules

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ci.yml"
MODULE_PATHS = {
    "shared": "prism-shared",
    "protocol": "prism-protocol",
    "channel": "prism-channel",
    "routing": "prism-routing",
    "resilience": "prism-resilience",
    "oauth": "prism-subscription-oauth",
    "quota": "prism-quota",
    "pricing": "prism-pricing",
    "requestlog": "prism-request-log",
    "admin": "prism-admin",
    "app": "prism-app",
}
ALL_UPSTREAM = (
    "prism-shared,prism-protocol,prism-channel,prism-routing,prism-resilience,"
    "prism-subscription-oauth,prism-quota,prism-pricing,prism-request-log,prism-admin"
)
PROTOCOL_CHANNEL_CASES = (
    (("protocol",), "prism-protocol,prism-routing", "protocol"),
    (("channel",), "prism-channel,prism-routing", "channel"),
    (("protocol", "channel"), "prism-protocol,prism-channel,prism-routing", "channel|protocol"),
)


def yaml_block(source: str, header: str) -> str:
    """Read one known indentation block, without requiring a YAML dependency."""
    lines = source.splitlines()
    start = lines.index(header)
    indent = len(header) - len(header.lstrip())
    end = start + 1
    while end < len(lines):
        line = lines[end]
        if line.strip() and not line.lstrip().startswith("#"):
            if len(line) - len(line.lstrip()) <= indent:
                break
        end += 1
    return "\n".join(lines[start + 1:end])


def environment_for(*flags: str) -> dict[str, str]:
    return {f"CHANGED_{flag.upper()}": "true" if flag in flags else "false" for flag in CHANGE_FLAGS}


class ModuleSelectionTests(unittest.TestCase):
    def assert_selection(self, flags, modules, app_tests="true", app_tags=""):
        self.assertEqual(
            resolve_modules({flag: True for flag in flags}),
            {"modules": modules, "app_tests": app_tests, "app_tags": app_tags},
        )

    def test_admin_only_runs_admin_and_full_app(self):
        self.assert_selection(("admin",), "prism-admin")

    def test_admin_mixed_with_protocol_channel_requires_full_app(self):
        for flags, modules, _ in PROTOCOL_CHANNEL_CASES:
            with self.subTest(flags=flags):
                self.assert_selection((*flags, "admin"), modules.replace(
                    "prism-routing", "prism-admin,prism-routing"
                ))

    def test_shared_and_build_run_every_upstream_and_full_app(self):
        for flags in (("shared",), ("build",), ("shared", "build")):
            for mixed in ((), ("admin",), ("protocol", "channel", "admin")):
                with self.subTest(flags=flags, mixed=mixed):
                    self.assert_selection((*flags, *mixed), ALL_UPSTREAM)

    def test_full_upstream_list_matches_reactor_without_app(self):
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        modules = [element.text for element in ET.parse(ROOT / "pom.xml").findall("m:modules/m:module", namespace)]
        self.assertEqual(ALL_UPSTREAM.split(","), [module for module in modules if module != "prism-app"])
        self.assert_selection(("build",), ALL_UPSTREAM)

    def test_original_protocol_channel_tag_selection(self):
        for flags, modules, tags in PROTOCOL_CHANNEL_CASES:
            with self.subTest(flags=flags):
                self.assert_selection(flags, modules, app_tags=tags)

    def test_other_upstreams_and_app_run_full_app_tests(self):
        for flag in ("routing", "resilience", "oauth", "quota", "pricing", "requestlog", "app"):
            with self.subTest(flag=flag):
                self.assert_selection((flag,), "" if flag == "app" else MODULE_PATHS[flag])

    def test_full_app_flags_disable_protocol_channel_trimming(self):
        for flag in ("routing", "resilience", "oauth", "quota", "pricing", "requestlog", "admin", "app"):
            for upstreams, modules, _ in PROTOCOL_CHANNEL_CASES:
                with self.subTest(flag=flag, upstreams=upstreams):
                    expected = modules
                    if flag not in ("app", "routing"):
                        expected = modules.replace("prism-routing", f"{MODULE_PATHS[flag]},prism-routing")
                    self.assert_selection((*upstreams, flag), expected)

    def test_requestlog_and_admin_each_remain_selected(self):
        self.assert_selection(("requestlog", "admin"), "prism-request-log,prism-admin")

    def test_no_relevant_module_changes_skip_app(self):
        self.assert_selection((), "", app_tests="false")
        # Extensions are tested by their own workflow step, not by app tests.
        self.assert_selection(("extensions",), "", app_tests="false")

    def test_all_combinations_have_unique_modules_and_safe_tags(self):
        for values in itertools.product((False, True), repeat=len(CHANGE_FLAGS)):
            changes = dict(zip(CHANGE_FLAGS, values))
            result = resolve_modules(changes)
            with self.subTest(changes=changes):
                modules = result["modules"].split(",") if result["modules"] else []
                self.assertEqual(len(modules), len(set(modules)))
                self.assertTrue(set(modules) <= set(ALL_UPSTREAM.split(",")))
                self.assertIn(result["app_tags"], ("", "channel", "protocol", "channel|protocol"))
                if changes["admin"] or changes["shared"] or changes["build"]:
                    self.assertIn("prism-admin", modules)
                    self.assertEqual(result["app_tests"], "true")
                    self.assertEqual(result["app_tags"], "")

    def test_environment_requires_literal_booleans(self):
        expected_flags = {"build", *MODULE_PATHS}
        self.assertEqual(set(CHANGE_FLAGS), expected_flags)
        for flag in CHANGE_FLAGS:
            with self.subTest(flag=flag):
                self.assertEqual(changes_from_env(environment_for(flag)), {
                    name: name == flag for name in CHANGE_FLAGS
                })
            for value in (None, "", "True", "1", "true\napp_tags=channel", 'true"; $(exit 0)'):
                environment = environment_for()
                name = f"CHANGED_{flag.upper()}"
                if value is None:
                    del environment[name]
                else:
                    environment[name] = value
                with self.subTest(flag=flag, value=value):
                    with self.assertRaisesRegex(ValueError, name):
                        changes_from_env(environment)


class WorkflowWiringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.changes = yaml_block(cls.workflow, "  changes:")
        cls.filters = yaml_block(cls.changes, "          filters: |")
        cls.backend = yaml_block(cls.workflow, "  backend:")
        cls.resolver = yaml_block(cls.backend, "      - name: Resolve affected test modules")

    def test_module_filters_are_independent_and_in_backend_paths(self):
        backend_paths = yaml_block(self.filters, "            backend:")
        outputs = yaml_block(self.changes, "    outputs:")
        for flag, module in MODULE_PATHS.items():
            with self.subTest(flag=flag):
                self.assertEqual(yaml_block(self.filters, f"            {flag}:").strip(), f"- '{module}/**'")
                self.assertIn(f"- '{module}/**'", backend_paths)
                self.assertIn(f"      {flag}: ${{{{ steps.filter.outputs.{flag} }}}}", outputs)
        self.assertIn("      build: ${{ steps.filter.outputs.build }}", outputs)
        self.assertIn("      backend: ${{ steps.filter.outputs.backend }}", outputs)
        self.assertIn("    if: needs.changes.outputs.backend == 'true'", self.backend)

    def test_every_filter_boolean_is_passed_via_environment(self):
        self.assertIn("        id: modules", self.resolver)
        environment = yaml_block(self.resolver, "        env:")
        actual = dict(re.findall(r"^          (CHANGED_\w+): (.+)$", environment, re.M))
        expected = {
            f"CHANGED_{flag.upper()}": f"${{{{ needs.changes.outputs.{flag} }}}}"
            for flag in {"build", *MODULE_PATHS}
        }
        self.assertEqual(actual, expected)
        self.assertIn("        run: python3 .github/scripts/ci_test_modules.py", self.resolver)

    def test_workflow_runs_selection_regressions_for_script_changes(self):
        workflow_job = yaml_block(self.workflow, "  workflow:")
        regression = yaml_block(workflow_job, "      - name: Validate CI test module selection")
        self.assertIn("python3 -m py_compile .github/scripts/ci_test_modules.py", regression)
        self.assertIn("python3 .github/scripts/test_ci_test_modules.py", regression)
        self.assertIn("    if: needs.changes.outputs.ci == 'true'", workflow_job)
        self.assertIn("- '.github/scripts/**'", yaml_block(self.filters, "            ci:"))

    def test_upstream_and_app_steps_consume_resolver_outputs(self):
        upstream = yaml_block(self.backend, "      - name: Test affected upstream modules")
        self.assertIn("        if: steps.modules.outputs.modules != ''", upstream)
        self.assertIn("          TEST_MODULES: ${{ steps.modules.outputs.modules }}", upstream)
        self.assertIn('        run: mvn --batch-mode -pl "$TEST_MODULES" test', upstream)
        app = yaml_block(self.backend, "      - name: Test app module")
        self.assertIn("        if: steps.modules.outputs.app_tests == 'true'", app)
        self.assertIn("          APP_TAGS: ${{ steps.modules.outputs.app_tags }}", app)
        self.assertIn('if [ -z "$APP_TAGS" ]; then\n            mvn --batch-mode -pl prism-app test\n          else', app)
        self.assertIn('"channel"|"protocol") excluded="integration & !($APP_TAGS)" ;;', app)
        self.assertIn('"channel|protocol"|"protocol|channel") excluded="integration & !(channel|protocol)" ;;', app)
        self.assertIn('-DexcludedGroups="$excluded"', app)
        self.assertIn('-DfailIfNoTests=true', app)

    def test_module_shell_commands_do_not_interpolate_github_inputs(self):
        for name in ("Resolve affected test modules", "Test affected upstream modules", "Test app module"):
            step = yaml_block(self.backend, f"      - name: {name}")
            command = step.split("        run: ", 1)[1]
            with self.subTest(step=name):
                self.assertNotIn("${{", command)

    def run_resolver(self, environment):
        # Execute only the Python selector actually wired into the workflow.
        command = shlex.split(re.search(r"^        run: (.+)$", self.resolver, re.M).group(1))
        self.assertEqual(command, ["python3", ".github/scripts/ci_test_modules.py"])
        return subprocess.run(
            [sys.executable, "-B", str(ROOT / command[1])],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=10,
        )

    def test_wired_cli_appends_github_outputs(self):
        cases = (
            (("admin",), "prism-admin", ""),
            (("admin", "protocol", "channel"), "prism-protocol,prism-channel,prism-admin,prism-routing", ""),
            (("shared",), ALL_UPSTREAM, ""),
            (("protocol", "channel"), "prism-protocol,prism-channel,prism-routing", "channel|protocol"),
        )
        with tempfile.TemporaryDirectory() as directory:
            for flags, modules, tags in cases:
                with self.subTest(flags=flags):
                    output = Path(directory) / "github-output"
                    output.write_text("existing=preserved\n", encoding="utf-8")
                    environment = {**os.environ, **environment_for(*flags), "GITHUB_OUTPUT": str(output)}
                    result = self.run_resolver(environment)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(output.read_text(encoding="utf-8"), (
                        f"existing=preserved\nmodules={modules}\napp_tests=true\napp_tags={tags}\n"
                    ))
                    self.assertIn("Affected upstream test modules:", result.stdout)

    def test_wired_cli_rejects_shell_input_without_writing_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            output.write_text("existing=preserved\n", encoding="utf-8")
            sentinel = Path(directory) / "must-not-exist"
            payload = f'true"; touch "{sentinel}"; #\napp_tags=channel'
            environment = {**os.environ, **environment_for("admin"), "GITHUB_OUTPUT": str(output)}
            environment["CHANGED_ADMIN"] = payload
            result = self.run_resolver(environment)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("CHANGED_ADMIN must be 'true' or 'false'", result.stderr)
            self.assertNotIn(payload, result.stderr)
            self.assertEqual(output.read_text(encoding="utf-8"), "existing=preserved\n")
            self.assertFalse(sentinel.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)

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

from ci_test_modules import ALL_UPSTREAM_MODULES, CHANGE_FLAGS, boolean_from_env, changes_from_env, resolve_modules

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ci.yml"
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
# Existing filter flags are deliberately separate from the complete reactor graph.
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
INFRA_MODULES = ("prism-db-migrations", "prism-test-support")
REACTOR_MODULES = (*INFRA_MODULES, *MODULE_PATHS.values())
# The sole no-test exception is enforced as resources-only below. Support is a
# normal jar with executable pure contract tests, not another no-test exception.
NO_TEST_MODULES = {"prism-db-migrations"}
RUNNABLE_MODULES = tuple(module for module in REACTOR_MODULES if module not in NO_TEST_MODULES)
ALL_UPSTREAM = (
    "prism-test-support,prism-shared,prism-protocol,prism-channel,prism-routing,prism-resilience,"
    "prism-subscription-oauth,prism-quota,prism-pricing,prism-request-log,prism-admin"
)
MOVED_COMPONENT_OWNERS = {
    "prism-request-log", "prism-admin", "prism-quota", "prism-subscription-oauth",
}
PROTOCOL_CHANNEL_CASES = (
    (("protocol",), "prism-protocol,prism-routing,prism-request-log", "protocol"),
    (("channel",), "prism-channel,prism-routing", "channel"),
    (("protocol", "channel"), "prism-protocol,prism-channel,prism-routing,prism-request-log", "channel|protocol"),
)
CI_BUILD_PATHS = (
    ".github/workflows/ci.yml",
    ".github/scripts/ci_test_modules.py",
    ".github/scripts/test_ci_test_modules.py",
    ".github/scripts/ci_test_report.py",
    ".github/scripts/test_ci_test_report.py",
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


def environment_for(*flags: str, full_regression: bool = False) -> dict[str, str]:
    return {
        **{f"CHANGED_{flag.upper()}": "true" if flag in flags else "false" for flag in CHANGE_FLAGS},
        "FULL_REGRESSION": "true" if full_regression else "false",
    }


def ordered_modules(modules):
    return ",".join(module for module in ALL_UPSTREAM.split(",") if module in modules)


def reactor_modules():
    return tuple(element.text for element in ET.parse(ROOT / "pom.xml").findall("m:modules/m:module", NAMESPACE))


def module_dependency_scopes(module):
    """Read all direct internal dependencies, including test-scoped graph edges."""
    pom = ET.parse(ROOT / module / "pom.xml")
    return {
        dependency.findtext("m:artifactId", namespaces=NAMESPACE): dependency.findtext(
            "m:scope", default="compile", namespaces=NAMESPACE
        )
        for dependency in pom.findall("m:dependencies/m:dependency", NAMESPACE)
        if dependency.findtext("m:groupId", namespaces=NAMESPACE) == "dev.soven"
    }


def dependency_closure(roots, graph, downstream=False):
    reached = set(roots)
    while True:
        expanded = reached | (
            {module for module, dependencies in graph.items() if dependencies & reached}
            if downstream else {dependency for module in reached for dependency in graph[module]}
        )
        if expanded == reached:
            return reached
        reached = expanded


def test_sources(module):
    """Static runnable-source check; JVM discovery remains the Kotlin contract's job."""
    sources = (ROOT / module / "src/test").rglob("*")
    return tuple(
        path for path in sources
        if path.suffix in (".kt", ".java")
        and re.fullmatch(r"(?:Test.+|.+Tests?|.+TestCase)", path.stem)
        and re.search(r"@(?:[\w.]+\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b", path.read_text(encoding="utf-8"))
    )


class ModuleSelectionTests(unittest.TestCase):
    def assert_selection(self, flags, modules, app_tests="true", app_tags="", full_regression=False):
        self.assertEqual(
            resolve_modules({flag: True for flag in flags}, full_regression=full_regression),
            {
                "modules": modules,
                "app_tests": app_tests,
                "app_tags": app_tags,
                "full_regression": "true" if full_regression else "false",
            },
        )

    def test_admin_only_runs_admin_and_full_app(self):
        self.assert_selection(("admin",), "prism-admin")

    def test_admin_mixed_with_protocol_channel_requires_full_app(self):
        for flags, modules, _ in PROTOCOL_CHANNEL_CASES:
            with self.subTest(flags=flags):
                self.assert_selection((*flags, "admin"), ordered_modules({*modules.split(","), "prism-admin"}))

    def test_shared_and_build_run_every_upstream_and_full_app(self):
        for flags in (("shared",), ("build",), ("shared", "build")):
            for mixed in ((), ("admin",), ("protocol", "channel", "admin")):
                with self.subTest(flags=flags, mixed=mixed):
                    self.assert_selection((*flags, *mixed), ALL_UPSTREAM)

    def test_full_upstream_list_is_runnable_partition_not_complete_reactor(self):
        self.assertEqual(REACTOR_MODULES, reactor_modules())
        self.assertEqual(tuple(ALL_UPSTREAM.split(",")), ALL_UPSTREAM_MODULES)
        self.assertEqual(
            ALL_UPSTREAM_MODULES,
            tuple(module for module in RUNNABLE_MODULES if module != "prism-app"),
        )
        self.assertEqual(ALL_UPSTREAM_MODULES[0], "prism-test-support")
        self.assertNotIn("prism-db-migrations", ALL_UPSTREAM_MODULES)
        self.assert_selection(("build",), ALL_UPSTREAM)

    def test_app_only_does_not_rerun_moved_components_or_support(self):
        self.assert_selection(("app",), "")
        for flags, modules, tags in PROTOCOL_CHANNEL_CASES:
            with self.subTest(flags=flags):
                result = resolve_modules({flag: True for flag in flags})
                selected = set(result["modules"].split(","))
                # Protocol genuinely affects request-log's existing test edge;
                # channel does not affect any moved component owner.
                expected_owners = {"prism-request-log"} if "protocol" in flags else set()
                self.assertEqual(selected & MOVED_COMPONENT_OWNERS, expected_owners)
                self.assertNotIn("prism-test-support", selected)
                self.assert_selection(flags, modules, app_tags=tags)

    def test_protocol_channel_keep_original_app_tag_selection(self):
        for flags, modules, tags in PROTOCOL_CHANNEL_CASES:
            with self.subTest(flags=flags):
                self.assert_selection(flags, modules, app_tags=tags)

    def test_pricing_selects_requestlog_without_duplicate_modules(self):
        for flags in (("pricing",), ("pricing", "requestlog")):
            self.assert_selection(flags, "prism-pricing,prism-request-log")
        self.assert_selection(
            ("protocol", "pricing", "requestlog"),
            "prism-protocol,prism-routing,prism-pricing,prism-request-log",
        )

    def test_other_upstreams_and_app_run_full_app_tests(self):
        for flag in ("routing", "resilience", "oauth", "quota", "requestlog", "app"):
            with self.subTest(flag=flag):
                self.assert_selection((flag,), "" if flag == "app" else MODULE_PATHS[flag])

    def test_full_app_flags_disable_protocol_channel_trimming(self):
        for flag in ("routing", "resilience", "oauth", "quota", "pricing", "requestlog", "admin", "app"):
            for upstreams, modules, _ in PROTOCOL_CHANNEL_CASES:
                with self.subTest(flag=flag, upstreams=upstreams):
                    expected = set(modules.split(","))
                    if flag != "app":
                        expected.add(MODULE_PATHS[flag])
                    if flag == "pricing":
                        expected.add("prism-request-log")
                    self.assert_selection((*upstreams, flag), ordered_modules(expected))

    def test_requestlog_and_admin_each_remain_selected(self):
        self.assert_selection(("requestlog", "admin"), "prism-request-log,prism-admin")

    def test_no_relevant_module_changes_skip_app(self):
        self.assert_selection((), "", app_tests="false")
        # Extensions are tested by their own workflow step, not by app tests.
        self.assert_selection(("extensions",), "", app_tests="false")

    def test_full_regression_overrides_every_incremental_combination(self):
        for values in itertools.product((False, True), repeat=len(CHANGE_FLAGS)):
            flags = tuple(flag for flag, changed in zip(CHANGE_FLAGS, values) if changed)
            with self.subTest(flags=flags):
                self.assert_selection(flags, ALL_UPSTREAM, full_regression=True)

    def test_all_combinations_have_unique_reactor_order_modules_and_safe_tags(self):
        for values in itertools.product((False, True), repeat=len(CHANGE_FLAGS)):
            changes = dict(zip(CHANGE_FLAGS, values))
            result = resolve_modules(changes)
            with self.subTest(changes=changes):
                modules = result["modules"].split(",") if result["modules"] else []
                self.assertEqual(len(modules), len(set(modules)))
                self.assertEqual(result["modules"], ordered_modules(modules))
                self.assertIn(result["app_tags"], ("", "channel", "protocol", "channel|protocol"))
                if changes["admin"] or changes["shared"] or changes["build"]:
                    self.assertIn("prism-admin", modules)
                    self.assertEqual(result["app_tests"], "true")
                    self.assertEqual(result["app_tags"], "")

    def test_selection_matches_actual_production_and_test_pom_closure(self):
        modules = set(reactor_modules())
        self.assertEqual(modules, set(REACTOR_MODULES))
        dependencies = {module: set(module_dependency_scopes(module)) for module in REACTOR_MODULES}
        for module, upstreams in dependencies.items():
            with self.subTest(module=module):
                self.assertTrue(upstreams <= modules, f"Unregistered internal dependencies: {upstreams - modules}")
        runnable = set(RUNNABLE_MODULES)
        for values in itertools.product((False, True), repeat=len(CHANGE_FLAGS)):
            changes = dict(zip(CHANGE_FLAGS, values))
            if changes["build"] or changes["shared"]:
                expected = runnable
            else:
                changed_modules = {module for flag, module in MODULE_PATHS.items() if changes[flag]}
                expected = dependency_closure(changed_modules, dependencies, downstream=True) & runnable
            selection = resolve_modules(changes)
            selected = set(filter(None, selection["modules"].split(",")))
            if selection["app_tests"] == "true":
                selected.add("prism-app")
            with self.subTest(changes=changes):
                self.assertEqual(selected, expected)
        # Infra changes do not get new CHANGED_* inputs. Each maps to build,
        # whose full runnable selection must include all actual consumers.
        for module in INFRA_MODULES:
            affected = dependency_closure({module}, dependencies, downstream=True) & runnable
            selection = resolve_modules({"build": True})
            selected = set(selection["modules"].split(",")) | {"prism-app"}
            with self.subTest(changed=module):
                self.assertTrue(affected <= selected, f"Missing infra consumer tests: {affected - selected}")
                self.assertIn("prism-test-support", affected)
                self.assertTrue(MOVED_COMPONENT_OWNERS | {"prism-app"} <= affected)

    def test_infra_scopes_keep_support_out_of_production_closure(self):
        self.assertEqual(module_dependency_scopes("prism-db-migrations"), {})
        self.assertEqual(module_dependency_scopes("prism-test-support"), {"prism-db-migrations": "compile"})
        for module in (*MOVED_COMPONENT_OWNERS, "prism-app"):
            with self.subTest(module=module):
                self.assertEqual(module_dependency_scopes(module).get("prism-test-support"), "test")
        self.assertEqual(module_dependency_scopes("prism-app").get("prism-db-migrations"), "compile")
        self.assertEqual(module_dependency_scopes("prism-request-log").get("prism-protocol"), "test")
        production = {
            module: {dependency for dependency, scope in module_dependency_scopes(module).items() if scope in ("compile", "runtime")}
            for module in REACTOR_MODULES
        }
        closure = dependency_closure({"prism-app"}, production)
        self.assertIn("prism-db-migrations", closure)
        self.assertNotIn("prism-test-support", closure)

    def test_migrations_resources_only_and_support_has_real_tests(self):
        migrations = ROOT / "prism-db-migrations"
        self.assertTrue(tuple((migrations / "src/main/resources/db/migration").glob("V*__*.sql")))
        self.assertFalse(tuple(path for path in (migrations / "src/test").rglob("*") if path.is_file()))
        self.assertFalse(tuple(path for path in (migrations / "src").rglob("*") if path.suffix in (".java", ".kt")))
        for module in REACTOR_MODULES:
            with self.subTest(module=module):
                self.assertEqual(bool(test_sources(module)), module in RUNNABLE_MODULES)
        support_pom = ET.parse(ROOT / "prism-test-support/pom.xml")
        self.assertEqual(support_pom.findtext("m:packaging", default="jar", namespaces=NAMESPACE), "jar")
        self.assertTrue(tuple((ROOT / "prism-test-support/src/main/kotlin").rglob("*.kt")))
        for module in (*MOVED_COMPONENT_OWNERS, "prism-app"):
            pom = ET.parse(ROOT / module / "pom.xml")
            support = [
                dependency for dependency in pom.findall("m:dependencies/m:dependency", NAMESPACE)
                if dependency.findtext("m:artifactId", namespaces=NAMESPACE) == "prism-test-support"
            ]
            with self.subTest(module=module):
                self.assertEqual(len(support), 1)
                self.assertEqual(support[0].findtext("m:type", default="jar", namespaces=NAMESPACE), "jar")
                self.assertIsNone(support[0].findtext("m:classifier", namespaces=NAMESPACE))
        self.assertFalse((ROOT / "prism-app/src/main/resources/db/migration").exists())

    def test_environment_requires_literal_booleans(self):
        self.assertEqual(set(CHANGE_FLAGS), {"build", *MODULE_PATHS})
        for flag in CHANGE_FLAGS:
            self.assertEqual(changes_from_env(environment_for(flag)), {
                name: name == flag for name in CHANGE_FLAGS
            })
        for name in (*[f"CHANGED_{flag.upper()}" for flag in CHANGE_FLAGS], "FULL_REGRESSION"):
            for value in (None, "", "True", "1", "true\napp_tags=channel", 'true"; $(exit 0)'):
                environment = environment_for()
                if value is None:
                    del environment[name]
                else:
                    environment[name] = value
                with self.subTest(name=name, value=value):
                    with self.assertRaisesRegex(ValueError, name):
                        boolean_from_env(environment, name)
        self.assertTrue(boolean_from_env(environment_for(full_regression=True), "FULL_REGRESSION"))


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

    def test_infra_paths_trigger_backend_and_build_without_new_flags(self):
        outputs = yaml_block(self.changes, "    outputs:")
        environment = yaml_block(self.resolver, "        env:")
        for module in INFRA_MODULES:
            for header in ("            backend:", "            build:"):
                with self.subTest(module=module, header=header):
                    self.assertIn(f"- '{module}/**'", yaml_block(self.filters, header))
            self.assertNotIn(f"            {module}:", self.filters)
            self.assertNotIn(module, outputs)
            self.assertNotIn(module, environment)
        self.assertEqual(set(re.findall(r"^            (\w+):$", self.filters, re.M)), {
            "frontend", "backend", *CHANGE_FLAGS, "extensions", "monitoring", "deps", "ci",
        })

    def test_docker_build_copies_every_reactor_module(self):
        dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
        build_stage = dockerfile.split("FROM maven:", 1)[1].split("\nFROM ", 1)[0]
        copies = re.findall(r"^COPY (prism-[\w-]+) \./(prism-[\w-]+)$", build_stage, re.M)
        self.assertEqual(copies, [(module, module) for module in reactor_modules()])
        self.assertIn("COPY pom.xml ./", build_stage)
        self.assertIn("-DskipTests -pl prism-app -am package", build_stage)

    def test_ci_execution_changes_trigger_backend_and_full_regression(self):
        for header in ("            backend:", "            build:"):
            block = yaml_block(self.filters, header)
            for path in CI_BUILD_PATHS:
                with self.subTest(header=header, path=path):
                    self.assertIn(f"- '{path}'", block)

    def test_every_filter_boolean_and_main_mode_are_passed_via_environment(self):
        self.assertIn("        id: modules", self.resolver)
        environment = yaml_block(self.resolver, "        env:")
        actual = dict(re.findall(r"^          (CHANGED_\w+): (.+)$", environment, re.M))
        expected = {
            f"CHANGED_{flag.upper()}": f"${{{{ needs.changes.outputs.{flag} }}}}"
            for flag in {"build", *MODULE_PATHS}
        }
        self.assertEqual(actual, expected)
        self.assertIn("FULL_REGRESSION: ${{ github.event_name == 'push' && github.ref == 'refs/heads/main' }}", environment)
        self.assertIn("        run: python3 .github/scripts/ci_test_modules.py", self.resolver)

    def test_workflow_runs_selection_and_reporting_regressions(self):
        workflow_job = yaml_block(self.workflow, "  workflow:")
        regression = yaml_block(workflow_job, "      - name: Validate CI test module selection")
        for script in ("ci_test_modules", "ci_test_report"):
            self.assertIn(f"python3 -m py_compile .github/scripts/{script}.py", regression)
            self.assertIn(f"python3 .github/scripts/test_{script}.py", regression)
        self.assertIn("    if: needs.changes.outputs.ci == 'true'", workflow_job)
        self.assertIn("- '.github/scripts/**'", yaml_block(self.filters, "            ci:"))

    def test_compile_precedes_direct_surefire_and_uses_current_checkout(self):
        compile_step = yaml_block(self.backend, "      - name: Compile backend and install plugin SPI")
        upstream = yaml_block(self.backend, "      - name: Test affected upstream modules")
        self.assertIn("mvn --batch-mode --no-transfer-progress -DskipTests install", compile_step)
        self.assertLess(self.backend.index("Compile backend"), self.backend.index("Test affected upstream modules"))
        self.assertIn("        if: steps.modules.outputs.modules != ''", upstream)
        self.assertIn("          TEST_MODULES: ${{ steps.modules.outputs.modules }}", upstream)
        self.assertIn('mvn --batch-mode --no-transfer-progress -pl "$TEST_MODULES" surefire:test', upstream)
        self.assertIn("-DfailIfNoTests=true", upstream)
        self.assertNotIn("actions/cache", self.backend)

    def test_app_keeps_original_selection_in_one_surefire_execution(self):
        app = yaml_block(self.backend, "      - name: Test app module")
        self.assertIn("        if: steps.modules.outputs.app_tests == 'true'", app)
        self.assertIn("          APP_TAGS: ${{ steps.modules.outputs.app_tags }}", app)
        self.assertIn('if [ -z "$APP_TAGS" ]; then', app)
        self.assertEqual(app.count("-pl prism-app surefire:test"), 2)
        self.assertIn('"channel"|"protocol") excluded="integration & !($APP_TAGS)" ;;', app)
        self.assertIn('"channel|protocol"|"protocol|channel") excluded="integration & !(channel|protocol)" ;;', app)
        self.assertIn('-DexcludedGroups="$excluded"', app)
        self.assertEqual(app.count("-DfailIfNoTests=true"), 2)
        self.assertNotIn("-Dgroups", app)

    def test_extensions_keep_verify_and_main_full_baseline(self):
        step = yaml_block(self.backend, "      - name: Test optional quota providers")
        self.assertIn("steps.modules.outputs.full_regression == 'true'", step)
        self.assertIn("-f extensions/quota-providers/pom.xml verify", step)

    def test_reports_are_preserved_even_on_failure(self):
        for name in ("Summarize backend tests", "Preserve backend test reports"):
            step = yaml_block(self.backend, f"      - name: {name}")
            self.assertIn("        if: always()", step)
        self.assertIn("target/ci-sanitized-reports/", self.backend)
        self.assertNotIn("prism-*/target/surefire-reports/", self.backend)
        self.assertIn("target/ci-test-timings.json", self.backend)

    def test_module_shell_commands_do_not_interpolate_github_inputs(self):
        for name in ("Resolve affected test modules", "Test affected upstream modules", "Test app module"):
            step = yaml_block(self.backend, f"      - name: {name}")
            command = step.split("        run: ", 1)[1]
            with self.subTest(step=name):
                self.assertNotIn("${{", command)

    def run_resolver(self, environment):
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
            (("admin",), "prism-admin", "", False),
            (("admin", "protocol", "channel"), "prism-protocol,prism-channel,prism-routing,prism-request-log,prism-admin", "", False),
            (("shared",), ALL_UPSTREAM, "", False),
            (("protocol", "channel"), "prism-protocol,prism-channel,prism-routing,prism-request-log", "channel|protocol", False),
            (("channel",), ALL_UPSTREAM, "", True),
            ((), ALL_UPSTREAM, "", True),
        )
        with tempfile.TemporaryDirectory() as directory:
            for flags, modules, tags, full in cases:
                with self.subTest(flags=flags, full=full):
                    output = Path(directory) / "github-output"
                    output.write_text("existing=preserved\n", encoding="utf-8")
                    environment = {**os.environ, **environment_for(*flags, full_regression=full), "GITHUB_OUTPUT": str(output)}
                    result = self.run_resolver(environment)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(output.read_text(encoding="utf-8"), (
                        f"existing=preserved\nmodules={modules}\napp_tests=true\napp_tags={tags}\n"
                        f"full_regression={'true' if full else 'false'}\n"
                    ))
                    self.assertIn("Affected upstream test modules:", result.stdout)

    def test_wired_cli_rejects_invalid_input_without_writing_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            sentinel = Path(directory) / "must-not-exist"
            payload = f'true"; touch "{sentinel}"; #\napp_tags=channel'
            for name in ("CHANGED_ADMIN", "FULL_REGRESSION"):
                output.write_text("existing=preserved\n", encoding="utf-8")
                environment = {**os.environ, **environment_for("admin"), "GITHUB_OUTPUT": str(output), name: payload}
                result = self.run_resolver(environment)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f"{name} must be 'true' or 'false'", result.stderr)
                self.assertNotIn(payload, result.stderr)
                self.assertEqual(output.read_text(encoding="utf-8"), "existing=preserved\n")
                self.assertFalse(sentinel.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)

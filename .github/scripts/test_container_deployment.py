import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import textwrap
import unittest

from test_ci_test_modules import yaml_block


ROOT = Path(__file__).resolve().parents[2]
SOURCE_SHA = "a" * 40


class PublicationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / ".github/workflows/publish-image.yml").read_text()
        step = yaml_block(cls.workflow, "      - name: Verify source and successful CI")
        cls.script = textwrap.dedent(yaml_block(step, "        run: |"))
        promotion = yaml_block(cls.workflow, "      - name: Promote current source to main")
        cls.promotion_script = textwrap.dedent(yaml_block(promotion, "        run: |"))

    def run_gate(self, main_sha=SOURCE_SHA, successful_runs="1", api_failure="0", script=None):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            executable = temporary / "gh"
            executable.write_text(textwrap.dedent("""\
                #!/bin/sh
                printf '%s\n' "$*" >> "$TEST_GH_LOG"
                if [ "$TEST_GH_FAILURE" = "1" ]; then
                  exit 1
                fi
                case "$*" in
                  *git/ref/heads/main*) printf '%s\n' "$TEST_MAIN_SHA" ;;
                  *actions/workflows/ci.yml/runs*) printf '%s\n' "$TEST_SUCCESSFUL_RUNS" ;;
                  *) exit 2 ;;
                esac
                """))
            executable.chmod(0o700)
            docker = temporary / "docker"
            docker.write_text('#!/bin/sh\nprintf "%s\\n" "docker $*" >> "$TEST_GH_LOG"\n')
            docker.chmod(0o700)
            output = temporary / "output"
            output.touch()
            log = temporary / "gh.log"
            environment = {
                **os.environ,
                "PATH": f"{temporary}{os.pathsep}{os.environ['PATH']}",
                "SOURCE_SHA": SOURCE_SHA,
                "GITHUB_REPOSITORY": "EJCrystal/PrismLLM",
                "GITHUB_OUTPUT": str(output),
                "IMAGE": "ghcr.io/ejcrystal/prismllm",
                "DIGEST": f"sha256:{'b' * 64}",
                "TEST_GH_LOG": str(log),
                "TEST_MAIN_SHA": main_sha,
                "TEST_SUCCESSFUL_RUNS": successful_runs,
                "TEST_GH_FAILURE": api_failure,
            }
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script or self.script],
                env=environment,
                capture_output=True,
                text=True,
            )
            return result, output.read_text(), log.read_text()

    def test_successful_ci_allows_publication_and_lowercases_image(self):
        result, output, log = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("publish=true\n", output)
        self.assertIn("image=ghcr.io/ejcrystal/prismllm\n", output)
        self.assertIn("--method GET", log)
        self.assertIn(f"-f branch=main -f event=push -f head_sha={SOURCE_SHA} -f status=success", log)

    def test_missing_successful_ci_blocks_publication(self):
        result, output, _ = self.run_gate(successful_runs="0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("successful main CI run", result.stdout)
        self.assertNotIn("publish=true", output)

    def test_outdated_source_is_skipped_without_querying_ci(self):
        result, output, log = self.run_gate(main_sha="b" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, "publish=false\n")
        self.assertNotIn("actions/workflows", log)

    def test_api_failure_does_not_allow_publication(self):
        result, output, _ = self.run_gate(api_failure="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("publish=true", output)

    def test_only_successful_main_pushes_or_main_dispatch_can_publish(self):
        triggers = yaml_block(self.workflow, "on:")
        self.assertIn("workflows: [CI]", triggers)
        self.assertIn("types: [completed]", triggers)
        self.assertIn("branches: [main]", triggers)
        self.assertIn("workflow_dispatch:", triggers)
        self.assertNotIn("pull_request:", triggers)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", self.workflow)
        self.assertIn("github.event.workflow_run.event == 'push'", self.workflow)
        self.assertIn("github.event.workflow_run.head_repository.full_name == github.repository", self.workflow)
        self.assertIn("github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main'", self.workflow)

    def test_build_uses_verified_source_and_pinned_actions(self):
        checkout = yaml_block(self.workflow, "      - name: Check out verified source")
        self.assertIn("ref: ${{ env.SOURCE_SHA }}", checkout)
        self.assertIn("persist-credentials: false", checkout)
        actions = re.findall(r"^        uses: (.+)$", self.workflow, re.M)
        self.assertEqual(len(actions), 4)
        for action in actions:
            self.assertRegex(action, r"^[\w/-]+@[0-9a-f]{40}$")
        for name in ("Check out verified source", "Set up Docker Buildx", "Log in to GHCR", "Build and push image"):
            step = yaml_block(self.workflow, f"      - name: {name}")
            self.assertIn("if: steps.source.outputs.publish == 'true'", step)
        build = yaml_block(self.workflow, "      - name: Build and push image")
        self.assertIn("id: build", build)
        self.assertIn("context: .", build)
        self.assertIn("push: true", build)
        self.assertIn("${{ steps.source.outputs.image }}:sha-${{ env.SOURCE_SHA }}", build)
        self.assertNotIn("${{ steps.source.outputs.image }}:main", build)

    def test_main_tag_is_promoted_by_digest_after_rechecking_source(self):
        promotion = yaml_block(self.workflow, "      - name: Promote current source to main")
        self.assertIn("if: steps.source.outputs.publish == 'true'", promotion)
        self.assertIn("IMAGE: ${{ steps.source.outputs.image }}", promotion)
        self.assertIn("DIGEST: ${{ steps.build.outputs.digest }}", promotion)
        self.assertLess(
            self.workflow.index("      - name: Build and push image"),
            self.workflow.index("      - name: Promote current source to main"),
        )
        result, _, log = self.run_gate(script=self.promotion_script)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            f"docker buildx imagetools create --tag ghcr.io/ejcrystal/prismllm:main "
            f"ghcr.io/ejcrystal/prismllm@sha256:{'b' * 64}",
            log,
        )
        self.assertLess(log.index("git/ref/heads/main"), log.index("imagetools create"))

    def test_main_moving_during_build_keeps_only_sha_image(self):
        result, output, _ = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("publish=true", output)
        result, _, log = self.run_gate(main_sha="b" * 40, script=self.promotion_script)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("main changed during the build", result.stdout)
        self.assertNotIn("imagetools create", log)

    def test_failed_source_recheck_does_not_promote_main_tag(self):
        result, _, log = self.run_gate(api_failure="1", script=self.promotion_script)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("imagetools create", log)

    def test_compose_changes_trigger_deployment_validation(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        paths = yaml_block(workflow, "            ci:")
        for filename in ("docker-compose.yml", "docker-compose.build.yml"):
            with self.subTest(filename=filename):
                self.assertIn(f"- '{filename}'", paths)
        job = yaml_block(workflow, "  workflow:")
        self.assertIn("if: needs.changes.outputs.ci == 'true'", job)
        self.assertIn("python3 .github/scripts/test_container_deployment.py", job)


class ComposeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        for filename in ("docker-compose.yml", "docker-compose.build.yml"):
            shutil.copyfile(ROOT / filename, self.directory / filename)
        self.env_file = self.directory / ".env"
        self.env_file.write_text("GATEWAY_MASTER_KEY=existing-master-key\nADMIN_PASSWORD=existing-password\n")

    def config(self, local_build=False, image=None):
        command = ["docker", "compose", "-f", "docker-compose.yml"]
        if local_build:
            command.extend(["-f", "docker-compose.build.yml"])
        command.extend(["config", "--format", "json"])
        environment = {
            key: value for key, value in os.environ.items()
            if not key.startswith("COMPOSE_") and key != "PRISM_IMAGE"
        }
        if image:
            environment["PRISM_IMAGE"] = image
        result = subprocess.run(
            command,
            cwd=self.directory,
            env=environment,
            check=True,
            capture_output=True,
            text=True,
        )
        return json.loads(result.stdout)

    def test_default_deployment_uses_image_without_requiring_source(self):
        config = self.config()
        gateway = config["services"]["gateway"]
        self.assertEqual(gateway["image"], "ghcr.io/ejcrystal/prismllm:main")
        self.assertNotIn("build", gateway)
        self.assertFalse((self.directory / "Dockerfile").exists())

    def test_existing_database_and_network_names_are_preserved(self):
        config = self.config()
        self.assertEqual(config["name"], "prism-llm")
        self.assertEqual(config["volumes"]["pgdata"]["name"], "prism-llm_pgdata")
        self.assertEqual(config["networks"]["default"]["name"], "prism-llm_default")
        postgres = config["services"]["postgres"]
        self.assertEqual(postgres["image"], "postgres:16-alpine")
        self.assertEqual(postgres["volumes"][0]["source"], "pgdata")
        self.assertEqual(postgres["volumes"][0]["target"], "/var/lib/postgresql/data")
        self.assertEqual(postgres["environment"]["POSTGRES_DB"], "llm_gateway")

    def test_existing_secrets_and_readonly_plugin_mount_are_preserved(self):
        gateway = self.config()["services"]["gateway"]
        self.assertEqual(gateway["environment"]["GATEWAY_MASTER_KEY"], "existing-master-key")
        self.assertEqual(gateway["environment"]["ADMIN_PASSWORD"], "existing-password")
        self.assertEqual(gateway["environment"]["DATABASE_URL"], "jdbc:postgresql://postgres:5432/llm_gateway")
        mount = gateway["volumes"][0]
        self.assertEqual(mount["source"], str(self.directory / "plugins"))
        self.assertEqual(mount["target"], "/app/plugins")
        self.assertTrue(mount["read_only"])
        self.assertIn(gateway["stop_grace_period"], ("60s", "1m0s"))

    def test_image_can_be_pinned_in_existing_env_file(self):
        image = f"ghcr.io/ejcrystal/prismllm:sha-{SOURCE_SHA}"
        with self.env_file.open("a") as stream:
            stream.write(f"PRISM_IMAGE={image}\n")
        self.assertEqual(self.config()["services"]["gateway"]["image"], image)

    def test_image_can_be_pinned_by_digest_from_environment(self):
        image = f"ghcr.io/ejcrystal/prismllm@sha256:{'b' * 64}"
        self.assertEqual(self.config(image=image)["services"]["gateway"]["image"], image)

    def test_local_build_is_opt_in_and_does_not_pull_private_image(self):
        config = self.config(local_build=True)
        gateway = config["services"]["gateway"]
        self.assertEqual(gateway["image"], "prism-llm:local")
        self.assertEqual(gateway["build"]["context"], str(self.directory))
        self.assertEqual(gateway["pull_policy"], "never")
        self.assertEqual(config["volumes"]["pgdata"]["name"], "prism-llm_pgdata")


class DeploymentDocumentationTests(unittest.TestCase):
    def test_documented_readiness_uses_container_port_or_default(self):
        documentation = (ROOT / "docs/container-deployment.md").read_text()
        line = next(line for line in documentation.splitlines() if line.startswith("docker compose exec -T gateway "))
        command = shlex.split(line)
        self.assertEqual(command[:7], ["docker", "compose", "exec", "-T", "gateway", "sh", "-c"])
        with tempfile.TemporaryDirectory() as directory:
            curl = Path(directory) / "curl"
            curl.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            curl.chmod(0o700)
            for port in (None, "", "9090"):
                with self.subTest(port=port):
                    environment = {**os.environ, "PATH": f"{directory}{os.pathsep}{os.environ['PATH']}"}
                    environment.pop("PORT", None)
                    if port is not None:
                        environment["PORT"] = port
                    result = subprocess.run(command[5:], env=environment, check=True, capture_output=True, text=True)
                    self.assertIn(f"http://localhost:{port or '8080'}/health/ready", result.stdout.splitlines())


if __name__ == "__main__":
    unittest.main()

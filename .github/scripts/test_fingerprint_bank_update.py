import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from scripts.update_fingerprint_bank import (
    build_metadata,
    decode_f32_reference,
    model_family,
    parse_detector,
    publish_pair,
    update,
)
from test_ci_test_modules import yaml_block


def detector_fixture(model_ids, response_counts):
    header = {
        "schema": "shared-detector-v2",
        "bank_built_at": "2026-11-25T04:42:35.713Z",
        "model_ids": model_ids,
        "response_counts": {"$f32": 0, "n": len(response_counts)},
    }
    header_bytes = json.dumps(header, separators=(",", ":")).encode("utf-8")
    values = b"".join(struct.pack("<f", value) for value in response_counts)
    float_count = len(response_counts)
    planes = b"".join(values[offset::4] for offset in range(4))
    payload = struct.pack("<I", len(header_bytes)) + header_bytes
    payload += b"\0" * ((-len(payload)) % 4)
    return payload + planes


class FingerprintBankUpdateTests(unittest.TestCase):
    def test_decodes_upstream_float_plane_and_builds_metadata(self):
        binary = detector_fixture(["gpt-6.2-sol", "gemini-4-carbon"], [36, 48])
        header, start, float_count = parse_detector(binary)
        self.assertEqual([36.0, 48.0], decode_f32_reference(binary, start, float_count, header["response_counts"]))
        metadata = build_metadata(binary, header, start, float_count)
        self.assertEqual(["gpt-6.2-sol", "gemini-4-carbon"], [model["id"] for model in metadata["models"]])
        self.assertEqual([36, 48], [model["response_count"] for model in metadata["models"]])
        self.assertEqual(["GPT", "Gemini"], [model["family_name"] for model in metadata["models"]])

    def test_preserves_existing_display_metadata(self):
        binary = detector_fixture(["gpt-6-astra"], [72])
        header, start, float_count = parse_detector(binary)
        existing = {"models": [{"id": "gpt-6-astra", "display_name": "GPT Astra", "family": "gpt", "family_name": "GPT"}]}
        metadata = build_metadata(binary, header, start, float_count, existing)
        self.assertEqual("GPT Astra", metadata["models"][0]["display_name"])

    def test_unknown_family_fails_closed(self):
        with self.assertRaises(Exception):
            model_family("new-lab-1")


class PairedPublishTests(unittest.TestCase):
    """检测器与元数据必须成对更新：暂存失败不动目标，替换失败整体回滚。"""

    def _targets(self, directory):
        output = Path(directory)
        binary_path = output / "shared_detector.bin"
        metadata_path = output / "models.json"
        binary_path.write_bytes(b"old-bin")
        metadata_path.write_bytes(b"old-meta")
        return output, binary_path, metadata_path

    def test_successful_publish_replaces_both_files(self):
        with tempfile.TemporaryDirectory() as directory:
            output, binary_path, metadata_path = self._targets(directory)
            publish_pair([(binary_path, b"new-bin"), (metadata_path, b"new-meta")])
            self.assertEqual(b"new-bin", binary_path.read_bytes())
            self.assertEqual(b"new-meta", metadata_path.read_bytes())
            self.assertEqual([], [path.name for path in output.iterdir() if path.name.startswith(".")])

    def test_staging_failure_leaves_targets_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            output, binary_path, metadata_path = self._targets(directory)
            import scripts.update_fingerprint_bank as updater

            original = updater.staged_temp
            calls = {"count": 0}

            def failing_stage(path, content):
                calls["count"] += 1
                if calls["count"] == 2:
                    raise OSError("disk full")
                return original(path, content)

            with mock.patch.object(updater, "staged_temp", side_effect=failing_stage):
                with self.assertRaises(OSError):
                    updater.publish_pair([(binary_path, b"new-bin"), (metadata_path, b"new-meta")])

            self.assertEqual(b"old-bin", binary_path.read_bytes())
            self.assertEqual(b"old-meta", metadata_path.read_bytes())
            self.assertEqual([], [path.name for path in output.iterdir() if path.name.startswith(".")])

    def test_replace_failure_rolls_back_replaced_files(self):
        with tempfile.TemporaryDirectory() as directory:
            _, binary_path, metadata_path = self._targets(directory)
            import scripts.update_fingerprint_bank as updater

            real_replace = os.replace
            calls = {"count": 0}

            def failing_replace(source, target):
                calls["count"] += 1
                if calls["count"] == 2:
                    raise OSError("metadata replace failed")
                return real_replace(source, target)

            with mock.patch.object(updater.os, "replace", side_effect=failing_replace):
                with self.assertRaises(OSError):
                    updater.publish_pair([(binary_path, b"new-bin"), (metadata_path, b"new-meta")])

            self.assertEqual(b"old-bin", binary_path.read_bytes())
            self.assertEqual(b"old-meta", metadata_path.read_bytes())

    def test_update_repairs_a_mismatched_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            output, binary_path, metadata_path = self._targets(directory)
            # 旧元数据是合法 JSON，但与待发布的检测器 SHA 不匹配（模拟中断的成对发布）。
            metadata_path.write_text('{"models":[]}', encoding="utf-8")
            binary = detector_fixture(["gpt-6.2-sol"], [36])
            import scripts.update_fingerprint_bank as updater

            with mock.patch.object(updater, "download", return_value=binary):
                result = updater.update("owner/repo", "main", output, dry_run=False)

            self.assertTrue(result["changed"])
            self.assertEqual(binary, binary_path.read_bytes())
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            self.assertEqual(hashlib.sha256(binary).hexdigest(), metadata["source"]["detector_sha256"])


class WorkflowBranchBaseTests(unittest.TestCase):
    """Prepare update branch 必须总是基于 origin/main，而不是触发 workflow 的 ref。"""

    def setUp(self):
        self.environment = {
            **os.environ,
            "GIT_AUTHOR_NAME": "test",
            "GIT_AUTHOR_EMAIL": "test@example.com",
            "GIT_COMMITTER_NAME": "test",
            "GIT_COMMITTER_EMAIL": "test@example.com",
        }

    def git(self, cwd, *arguments):
        result = subprocess.run(["git", *arguments], cwd=cwd, env=self.environment, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def prepare_repository(self, temporary, with_existing_branch):
        origin = temporary / "origin.git"
        self.git(temporary, "init", "--bare", str(origin))
        work = temporary / "work"
        self.git(temporary, "init", "-b", "main", str(work))
        (work / "main.txt").write_text("main", encoding="utf-8")
        self.git(work, "add", "main.txt")
        self.git(work, "commit", "-m", "main")
        self.git(work, "remote", "add", "origin", str(origin))
        self.git(work, "push", "origin", "main")
        main_sha = self.git(work, "rev-parse", "HEAD")

        if with_existing_branch:
            self.git(work, "checkout", "-b", "stale-update")
            (work / "stale.txt").write_text("stale", encoding="utf-8")
            self.git(work, "add", "stale.txt")
            self.git(work, "commit", "-m", "stale update branch")
            self.git(work, "push", "origin", "HEAD:refs/heads/automation/update-fingerprint-bank")
            self.git(work, "checkout", "main")

        self.git(work, "checkout", "-b", "feature/dispatch")
        (work / "feature.txt").write_text("feature", encoding="utf-8")
        self.git(work, "add", "feature.txt")
        self.git(work, "commit", "-m", "feature commit")
        self.git(work, "push", "origin", "feature/dispatch")
        return work, main_sha

    def run_prepare_branch(self, work):
        workflow = (ROOT / ".github/workflows/update-fingerprint-bank.yml").read_text(encoding="utf-8")
        step = yaml_block(workflow, "      - name: Prepare update branch")
        script = textwrap.dedent(yaml_block(step, "        run: |"))
        binary_dir = work.parent / "bin"
        binary_dir.mkdir()
        gh = binary_dir / "gh"
        gh.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        gh.chmod(0o755)
        environment = {
            **self.environment,
            "PATH": f"{binary_dir}{os.pathsep}{os.environ['PATH']}",
            "UPDATE_BRANCH": "automation/update-fingerprint-bank",
            "GH_TOKEN": "test-token",
        }
        return subprocess.run(["bash", "-euo", "pipefail", "-c", script], cwd=work, env=environment, capture_output=True, text=True)

    def test_first_run_from_dispatch_branch_starts_from_main(self):
        with tempfile.TemporaryDirectory() as directory:
            work, main_sha = self.prepare_repository(Path(directory), with_existing_branch=False)
            result = self.run_prepare_branch(work)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("automation/update-fingerprint-bank", self.git(work, "branch", "--show-current"))
            self.assertEqual(main_sha, self.git(work, "rev-parse", "HEAD"))

    def test_push_uses_a_token_that_can_trigger_ci(self):
        # GITHUB_TOKEN 产生的 push/pull_request 事件不会启动其他工作流，
        # 自动更新 PR 必须用专用令牌推送与开 PR，否则永远等不到 CI 与审查。
        workflow = (ROOT / ".github/workflows/update-fingerprint-bank.yml").read_text(encoding="utf-8")
        for header in ("      - name: Prepare update branch", "      - name: Commit and open pull request"):
            step = yaml_block(workflow, header)
            self.assertIn("secrets.PR_AGENT_TRIGGER_TOKEN", step)
            self.assertNotIn("github.token", step)

    def test_existing_update_branch_is_reset_to_main(self):
        with tempfile.TemporaryDirectory() as directory:
            work, main_sha = self.prepare_repository(Path(directory), with_existing_branch=True)
            result = self.run_prepare_branch(work)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(main_sha, self.git(work, "rev-parse", "HEAD"))


if __name__ == "__main__":
    unittest.main()

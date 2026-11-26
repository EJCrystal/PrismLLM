import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import socket
import stat
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import antigravity_acp_pilot as pilot


class PilotPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "pilot"

    def test_dedicated_configuration_disables_extra_credits_and_api_provider(self):
        pilot.prepare_files(self.root)
        settings = self.root / "home/.gemini/antigravity-cli/settings.json"
        config = json.loads(settings.read_text())
        self.assertIs(config["useG1Credits"], False)
        self.assertEqual(config["modelProvider"], "")
        self.assertEqual(config["toolPermission"], "strict")
        self.assertEqual(config["permissions"]["allow"], [])
        self.assertEqual(stat.S_IMODE(self.root.stat().st_mode), 0o700)
        pilot.prepare_files(self.root)

    def test_changed_credit_policy_fails_closed(self):
        pilot.prepare_files(self.root)
        path = self.root / "policy.json"
        policy = json.loads(path.read_text())
        policy["useG1Credits"] = True
        path.write_text(json.dumps(policy))
        with self.assertRaises(pilot.PilotError):
            pilot.prepare_files(self.root)

    def test_private_directory_rejects_symlinks_and_shared_permissions(self):
        self.root.mkdir(mode=0o777)
        self.root.chmod(0o777)
        with self.assertRaises(pilot.PilotError):
            pilot.private_directory(self.root)
        link = Path(self.temporary.name) / "link"
        link.symlink_to(self.root)
        with self.assertRaises(pilot.PilotError):
            pilot.private_directory(link)

    def test_proxy_accepts_only_credential_free_loopback_http(self):
        self.assertEqual(("127.0.0.1", 7897), pilot.proxy_address("http://127.0.0.1:7897"))
        for value in ("http://user:secret@127.0.0.1:7897", "http://example.com:80",
                      "https://127.0.0.1:7897", "http://127.0.0.1:7897/private", "http://127.0.0.1:7897?token=x"):
            with self.subTest(value=value), self.assertRaises(pilot.PilotError):
                pilot.proxy_address(value)

    def test_egress_denies_host_services_api_key_endpoint_and_non_google_hosts(self):
        self.assertEqual("oauth2.googleapis.com", pilot.destination(b"CONNECT oauth2.googleapis.com:443 HTTP/1.1\r\n\r\n"))
        for host in ("127.0.0.1", "169.254.169.254", "generativelanguage.googleapis.com",
                     "oauth2.googleapis.com.evil.invalid", "example.com"):
            with self.subTest(host=host), self.assertRaises(pilot.PilotError):
                pilot.destination(f"CONNECT {host}:443 HTTP/1.1\r\n\r\n".encode())
        with self.assertRaises(pilot.PilotError):
            pilot.destination(b"GET https://oauth2.googleapis.com/ HTTP/1.1\r\n\r\n")

    def test_sandbox_unshares_network_and_mounts_only_pilot_state(self):
        command = pilot.sandbox_command(self.root, self.root / "runtime", True, ["/opt/agy", "acp"])
        self.assertIn("--unshare-all", command)
        self.assertNotIn("--share-net", command)
        self.assertNotIn(str(Path.home()), command)
        self.assertNotIn(str(pilot.REPO), command)
        self.assertIn("/run/pilot-egress.sock", command)
        self.assertNotIn("GEMINI_API_KEY", command)
        policy_index = command.index(str(self.root / "policy.json"))
        self.assertEqual("--ro-bind", command[policy_index - 1])
        for relative in ("home/.gemini", "home/.gemini/antigravity-cli"):
            directory_index = command.index(str(self.root / relative))
            self.assertEqual("--bind", command[directory_index - 1])
            self.assertLess(directory_index, policy_index)
        login = pilot.sandbox_command(self.root, self.root / "runtime", True, ["/opt/agy", "acp", "--login"], login=True)
        self.assertNotIn(str(self.root / "policy.json"), login)

    def test_bounded_proxy_headers(self):
        left, right = socket.socketpair()
        with left, right:
            right.sendall(b"x" * 16_385)
            with self.assertRaises(pilot.PilotError):
                pilot.read_header(left)

    def test_sanitized_report_is_saved_without_agent_frames(self):
        pilot.prepare_files(self.root)
        result = SimpleNamespace(stdout='{"status":"AUTHENTICATION_REQUIRED_OR_DENIED","realGenerationVerified":false}', returncode=10)
        with patch.object(Path, "exists", return_value=True), patch.object(pilot, "run_owned_process", return_value=result), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(10, pilot.run_checks(self.root, "http://127.0.0.1:7897", "probe"))
        report = json.loads((self.root / "reports/probe.json").read_text())
        self.assertIs(report["realGenerationVerified"], False)
        result.stdout = 'raw ACP frame\n' + result.stdout
        with patch.object(Path, "exists", return_value=True), patch.object(pilot, "run_owned_process", return_value=result):
            with self.assertRaises(pilot.PilotError):
                pilot.run_checks(self.root, "http://127.0.0.1:7897", "probe")

    def test_cli_logs_and_default_customizations_are_disabled(self):
        pilot.prepare_files(self.root)
        with patch.object(pilot.subprocess, "Popen") as popen:
            process = popen.return_value.__enter__.return_value
            process.communicate.return_value = (None, None)
            process.returncode = 0
            self.assertEqual(0, pilot.run_agent(self.root, "http://127.0.0.1:7897", False, ["acp"]))
        command = popen.call_args.args[0]
        self.assertIn("--disable-telemetry", command)
        self.assertIn("--include-default-customizations=false", command)
        self.assertEqual("/dev/null", command[command.index("--cli_log_file") + 1])
        self.assertEqual({"env": {}, "start_new_session": True}, popen.call_args.kwargs)

    def test_download_checks_archive_before_extracting_or_running(self):
        pilot.prepare_files(self.root)
        binary_data = b"fake binary, never execute"
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:gz") as output:
            member = tarfile.TarInfo("antigravity")
            member.size = len(binary_data)
            output.addfile(member, io.BytesIO(binary_data))
        payload = archive.getvalue()
        release = {"version": "fixture", "url": "https://storage.googleapis.com/antigravity-public/antigravity-cli/fixture.tar.gz",
                   "sha512": "incorrect"}
        opener = unittest.mock.Mock()
        opener.open.return_value = contextlib.closing(io.BytesIO(payload))
        with patch.object(pilot, "pinned_release", return_value=release), patch.object(pilot.urllib.request, "build_opener", return_value=opener):
            with self.assertRaises(pilot.PilotError):
                pilot.install_release(self.root, "http://127.0.0.1:7897")
        self.assertFalse((self.root / "bin/agy").exists())
        release["sha512"] = hashlib.sha512(payload).hexdigest()
        opener.open.return_value = contextlib.closing(io.BytesIO(payload))
        with patch.object(pilot, "pinned_release", return_value=release), patch.object(pilot.urllib.request, "build_opener", return_value=opener):
            pilot.install_release(self.root, "http://127.0.0.1:7897")
        self.assertEqual(binary_data, (self.root / "bin/agy").read_bytes())


class PilotInstallationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "pilot"
        pilot.prepare_files(self.root)
        self.binary = self.root / "bin/agy"
        self.receipt = self.root / "release.json"
        self.binary_data = b"offline CLI fixture; never execute"
        self.proxy = "http://127.0.0.1:7897"
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:gz") as output:
            member = tarfile.TarInfo("antigravity")
            member.size = len(self.binary_data)
            output.addfile(member, io.BytesIO(self.binary_data))
        self.payload = archive.getvalue()
        self.release = {"version": "fixture", "url": "https://storage.googleapis.com/antigravity-public/antigravity-cli/fixture.tar.gz",
                        "sha512": hashlib.sha512(self.payload).hexdigest()}
        self.record = {"release": self.release, "binarySha512": hashlib.sha512(self.binary_data).hexdigest()}
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(pilot, "pinned_release", return_value=self.release))
        self.opener = unittest.mock.Mock()
        self.opener.open.side_effect = lambda *arguments, **keywords: contextlib.closing(io.BytesIO(self.payload))
        self.build_opener = self.stack.enter_context(patch.object(pilot.urllib.request, "build_opener", return_value=self.opener))
        self.stack.enter_context(patch.object(pilot.subprocess, "Popen", side_effect=AssertionError("No real CLI")))
        self.stack.enter_context(patch.object(pilot.subprocess, "run", side_effect=AssertionError("No real subprocess")))

    def install(self):
        pilot.install_release(self.root, self.proxy)

    def write_binary(self, content=None):
        self.binary.write_bytes(self.binary_data if content is None else content)
        self.binary.chmod(0o700)

    def assert_installed(self):
        self.assertEqual(self.binary_data, self.binary.read_bytes())
        self.assertEqual(self.record, json.loads(self.receipt.read_text()))
        self.assertEqual(0o700, stat.S_IMODE(self.binary.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(self.receipt.stat().st_mode))
        self.assertEqual(1, self.binary.stat().st_nlink)
        self.assertEqual(1, self.receipt.stat().st_nlink)
        pilot.verify_installed_release(self.root, self.release)
        self.assertFalse(list(self.root.glob("download-*")))

    def test_success_publishes_verified_binary_and_private_receipt_then_reuses_without_download(self):
        self.install()
        self.assert_installed()
        binary_info, receipt_info = self.binary.stat(), self.receipt.stat()
        self.build_opener.reset_mock()
        self.install()
        self.build_opener.assert_not_called()
        self.assertEqual(binary_info, self.binary.stat())
        self.assertEqual(receipt_info, self.receipt.stat())

    def test_interrupted_or_failed_copy_never_publishes_partial_binary_and_retry_recovers(self):
        copyfileobj = pilot.shutil.copyfileobj
        for failure in (OSError("disk copy failed"), KeyboardInterrupt()):
            with self.subTest(failure=type(failure).__name__):
                def interrupted(source, output):
                    if Path(output.name).name == "agy":
                        output.write(source.read(4))
                        self.assertEqual(0o600, stat.S_IMODE(os.fstat(output.fileno()).st_mode))
                        self.assertFalse(self.binary.exists())
                        self.assertFalse(self.receipt.exists())
                        raise failure
                    copyfileobj(source, output)

                with patch.object(pilot.shutil, "copyfileobj", side_effect=interrupted):
                    with self.assertRaises(type(failure)):
                        self.install()
                self.assertFalse(self.binary.exists())
                self.assertFalse(self.receipt.exists())
                self.assertFalse(list(self.root.glob("download-*")))
                self.install()
                self.assert_installed()
                self.binary.unlink()
                self.receipt.unlink()

    def test_receipt_staging_failure_does_not_publish_binary(self):
        with patch.object(pilot, "private_json", side_effect=OSError("receipt disk full")):
            with self.assertRaises(OSError):
                self.install()
        self.assertFalse(self.binary.exists())
        self.assertFalse(self.receipt.exists())
        self.assertFalse(list(self.root.glob("download-*")))
        self.install()
        self.assert_installed()

    def test_interrupt_between_binary_and_receipt_recovers_without_replacing_binary(self):
        link = pilot.os.link

        def interrupted(source, destination):
            if destination == self.receipt:
                self.assertEqual(self.binary_data, self.binary.read_bytes())
                raise KeyboardInterrupt()
            link(source, destination)

        with patch.object(pilot.os, "link", side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                self.install()
        self.assertTrue(self.binary.exists())
        self.assertFalse(self.receipt.exists())
        self.assertFalse(list(self.root.glob("download-*")))
        binary_info = self.binary.stat()
        self.install()
        self.assert_installed()
        self.assertEqual(binary_info, self.binary.stat())

    def test_valid_receipt_only_state_recovers_without_replacing_receipt(self):
        pilot.private_json(self.receipt, self.record)
        receipt_info = self.receipt.stat()
        self.install()
        self.assert_installed()
        self.assertEqual(receipt_info, self.receipt.stat())

    def test_unknown_orphan_binary_is_preserved_instead_of_replaced(self):
        self.write_binary(b"unexpected existing file")
        original = self.binary.read_bytes(), self.binary.stat()
        with self.assertRaisesRegex(pilot.PilotError, "orphan CLI binary"):
            self.install()
        self.assertEqual(original, (self.binary.read_bytes(), self.binary.stat()))
        self.assertFalse(self.receipt.exists())
        self.assertFalse(list(self.root.glob("download-*")))

    def test_orphan_receipt_schema_and_pin_are_checked_before_download(self):
        for record in (None, [], {}, dict(self.record, release=dict(self.release, version="changed")),
                       dict(self.record, binarySha512=None), dict(self.record, binarySha512="invalid")):
            with self.subTest(record=record):
                pilot.private_json(self.receipt, record)
                original = self.receipt.read_bytes(), self.receipt.stat()
                with self.assertRaises(pilot.PilotError):
                    self.install()
                self.build_opener.assert_not_called()
                self.assertEqual(original, (self.receipt.read_bytes(), self.receipt.stat()))
                self.assertFalse(self.binary.exists())

    def test_orphan_receipt_digest_must_match_verified_archive(self):
        pilot.private_json(self.receipt, dict(self.record, binarySha512="0" * 128))
        original = self.receipt.read_bytes(), self.receipt.stat()
        with self.assertRaises(pilot.PilotError):
            self.install()
        self.assertEqual(original, (self.receipt.read_bytes(), self.receipt.stat()))
        self.assertFalse(self.binary.exists())
        self.assertFalse(list(self.root.glob("download-*")))

    def test_existing_invalid_pair_is_not_reinstalled_or_overwritten(self):
        for record in (dict(self.record, binarySha512="0" * 128),
                       dict(self.record, release=dict(self.release, version="changed"))):
            with self.subTest(record=record):
                self.write_binary()
                pilot.private_json(self.receipt, record)
                original = self.binary.read_bytes(), self.binary.stat(), self.receipt.read_bytes(), self.receipt.stat()
                with self.assertRaises(pilot.PilotError):
                    self.install()
                self.build_opener.assert_not_called()
                self.assertEqual(original, (self.binary.read_bytes(), self.binary.stat(), self.receipt.read_bytes(), self.receipt.stat()))

    def test_unexpected_symlinks_directories_and_special_paths_are_never_replaced(self):
        for path in (self.binary, self.receipt):
            for kind in ("symlink", "dangling-symlink", "directory", "fifo"):
                with self.subTest(path=path.name, kind=kind):
                    target = Path(self.temporary.name) / "outside"
                    target.write_bytes(b"leave outside file untouched")
                    if kind == "symlink":
                        path.symlink_to(target)
                    elif kind == "dangling-symlink":
                        path.symlink_to(target.with_name("absent"))
                    elif kind == "directory":
                        path.mkdir(mode=0o700)
                    else:
                        os.mkfifo(path, 0o600)
                    original = path.lstat()
                    try:
                        with self.assertRaises(pilot.PilotError):
                            self.install()
                        self.build_opener.assert_not_called()
                        self.assertEqual(original, path.lstat())
                        self.assertEqual(b"leave outside file untouched", target.read_bytes())
                    finally:
                        if kind == "directory":
                            path.rmdir()
                        else:
                            path.unlink()

    def test_private_permissions_ownership_and_binary_parent_are_required(self):
        for path, mode in ((self.binary, 0o755), (self.receipt, 0o644)):
            with self.subTest(path=path.name):
                path.write_bytes(b"unexpected shared file")
                path.chmod(mode)
                try:
                    with self.assertRaises(pilot.PilotError):
                        self.install()
                    self.build_opener.assert_not_called()
                    self.assertEqual(b"unexpected shared file", path.read_bytes())
                finally:
                    path.unlink()
        with patch.object(pilot.os, "getuid", return_value=os.getuid() + 1):
            with self.assertRaises(pilot.PilotError):
                self.install()
        directory = self.root / "bin"
        saved = self.root / "saved-bin"
        directory.rename(saved)
        directory.symlink_to(saved, target_is_directory=True)
        try:
            with self.assertRaises(pilot.PilotError):
                self.install()
            self.build_opener.assert_not_called()
        finally:
            directory.unlink()
            saved.rename(directory)

    def test_publish_never_clobbers_a_file_appearing_after_initial_checks(self):
        link = pilot.os.link
        for destination in (self.binary, self.receipt):
            with self.subTest(destination=destination.name):
                def appeared(source, target):
                    if target == destination:
                        target.write_bytes(b"concurrent unexpected file")
                        target.chmod(0o600)
                    link(source, target)

                with patch.object(pilot.os, "link", side_effect=appeared):
                    with self.assertRaises(FileExistsError):
                        self.install()
                self.assertEqual(b"concurrent unexpected file", destination.read_bytes())
                self.assertFalse(list(self.root.glob("download-*")))
                self.binary.unlink(missing_ok=True)
                self.receipt.unlink(missing_ok=True)


class GatewayAgentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "pilot"
        self.repo = Path(self.temporary.name) / "repo"
        (self.repo / "scripts").mkdir(parents=True)
        self.proxy = "http://127.0.0.1:7897"
        self.model = "gemini-4-hydrogen"
        self.report = {"status": "REAL_ACP_ACCEPTANCE_PASSED", "modelId": self.model,
                       "realGenerationVerified": True, "cancelAcknowledged": True, "credentialsReused": True}
        self.release = {"version": "fixture", "url": "https://storage.googleapis.com/antigravity-public/antigravity-cli/fixture.tar.gz",
                        "sha512": "a" * 128}
        self.pin = self.repo / "scripts/antigravity-acp-release.json"
        self.pin.write_text(json.dumps(self.release))
        pilot.prepare_files(self.root)
        (self.root / "pilot.lock").touch(mode=0o600)
        self.binary = self.root / "bin/agy"
        self.binary.write_bytes(b"test fixture; never execute")
        self.binary.chmod(0o700)
        self.receipt = {"release": self.release, "binarySha512": hashlib.sha512(self.binary.read_bytes()).hexdigest()}
        pilot.private_json(self.root / "release.json", self.receipt)
        self.verification = self.root / "reports/verification.json"
        pilot.private_json(self.verification, self.report)
        self.credentials = self.root / "home/.gemini/antigravity-cli/oauth_creds.json"
        self.credentials.write_bytes(b"mock credential must not be read by wrapper")
        self.credentials.chmod(0o600)
        self.real_run_agent = pilot.run_agent
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(pilot, "REPO", self.repo))
        self.stack.enter_context(patch.object(pilot.platform, "system", return_value="Linux"))
        self.stack.enter_context(patch.object(pilot.platform, "machine", return_value="x86_64"))
        self.agent = self.stack.enter_context(patch.object(pilot, "run_agent", return_value=0))
        self.installer = self.stack.enter_context(patch.object(pilot, "install_release", side_effect=AssertionError("No downloads")))
        self.stack.enter_context(patch.object(pilot.urllib.request, "build_opener", side_effect=AssertionError("No network")))
        self.stack.enter_context(patch.object(pilot.subprocess, "Popen", side_effect=AssertionError("No real CLI")))
        self.stack.enter_context(patch.object(pilot.subprocess, "run", side_effect=AssertionError("No real subprocess")))
        previous_umask = os.umask(0o077)
        self.addCleanup(os.umask, previous_umask)
        self.stdout = io.StringIO()

    def invoke(self, *arguments, state=None):
        command = ["pilot", "--state", str(self.root if state is None else state), "--proxy", self.proxy, *arguments]
        with patch.object(pilot.sys, "argv", command), contextlib.redirect_stdout(self.stdout):
            return pilot.main()

    def gateway(self, model=None):
        return self.invoke("gateway-agent", "--model", self.model if model is None else model)

    def snapshot(self):
        return {str(path.relative_to(self.root)): (stat.S_IMODE(path.lstat().st_mode), path.read_bytes())
                for path in self.root.rglob("*") if path.is_file()}

    def assert_gateway_denied(self):
        with self.assertRaises(pilot.PilotError):
            self.gateway()
        self.agent.assert_not_called()
        self.installer.assert_not_called()
        self.assertEqual("", self.stdout.getvalue())

    def test_gateway_is_silent_read_only_and_runs_only_online_acp(self):
        before = self.snapshot()
        original_open = Path.open

        def guarded_open(path, *arguments, **keywords):
            if path == self.credentials:
                raise AssertionError("Wrapper must not read credentials")
            return original_open(path, *arguments, **keywords)

        self.agent.return_value = 23
        with patch.object(pilot, "prepare_files", side_effect=AssertionError("No preparation")), patch.object(Path, "open", guarded_open):
            self.assertEqual(23, self.gateway())
        self.agent.assert_called_once_with(self.root, self.proxy, True, ["acp"])
        self.installer.assert_not_called()
        self.assertEqual("", self.stdout.getvalue())
        self.assertEqual(before, self.snapshot())

    def test_gateway_accepts_only_explicit_false_if_report_mentions_extra_credits(self):
        pilot.private_json(self.verification, dict(self.report, extraCredits=False))
        self.assertEqual(0, self.gateway())
        self.agent.assert_called_once_with(self.root, self.proxy, True, ["acp"])

    def test_missing_state_paths_are_never_recreated(self):
        for relative in ("", "pilot.lock", "policy.json", "release.json", "bin", "bin/agy", "home",
                         "reports", "reports/verification.json", "work", "home/.gemini",
                         "home/.gemini/antigravity-cli", "home/.gemini/antigravity-cli/settings.json"):
            with self.subTest(path=relative):
                path = self.root / relative
                saved = Path(self.temporary.name) / "saved"
                path.rename(saved)
                try:
                    self.assert_gateway_denied()
                    self.assertFalse(path.exists())
                finally:
                    saved.rename(path)

    def test_missing_or_changed_release_pin_fails_closed(self):
        self.pin.unlink()
        self.assert_gateway_denied()
        self.pin.write_text(json.dumps(dict(self.release, version="different")))
        self.assert_gateway_denied()

    def test_changed_binary_or_receipt_fails_without_downloading(self):
        original = self.binary.read_bytes()
        self.binary.write_bytes(b"changed executable")
        self.assert_gateway_denied()
        self.binary.write_bytes(original)
        for receipt in ({}, [], dict(self.receipt, binarySha512="0" * 128),
                        dict(self.receipt, release=dict(self.release, version="changed"))):
            with self.subTest(receipt=receipt):
                pilot.private_json(self.root / "release.json", receipt)
                self.assert_gateway_denied()

    def test_changed_policy_is_rejected_without_repairing_it(self):
        for relative in ("policy.json", "home/.gemini/antigravity-cli/settings.json"):
            for field, value in (("useG1Credits", True), ("useG1Credits", 0), ("modelProvider", "api-key"),
                                 ("toolPermission", "allow"), ("historySize", False), ("unknown", False)):
                with self.subTest(path=relative, field=field, value=value):
                    path = self.root / relative
                    changed = dict(pilot.POLICY, **{field: value})
                    pilot.private_json(path, changed)
                    self.assert_gateway_denied()
                    self.assertEqual(changed, json.loads(path.read_text()))
                    pilot.private_json(path, pilot.POLICY)

    def test_report_requires_pass_exact_model_and_no_paid_credits(self):
        for report in ({}, [], None, dict(self.report, status="AUTHENTICATION_REQUIRED_OR_DENIED"),
                       dict(self.report, modelId="different"), dict(self.report, modelId=self.model + " "),
                       dict(self.report, modelId=None), dict(self.report, extraCredits=True),
                       dict(self.report, extraCredits=0), dict(self.report, extraCredits="false"),
                       dict(self.report, extraCredits=None)):
            with self.subTest(report=report):
                pilot.private_json(self.verification, report)
                self.assert_gateway_denied()

    def test_report_requires_all_acceptance_flags_to_be_present(self):
        pilot.private_json(self.verification, {"status": self.report["status"], "modelId": self.model})
        self.assert_gateway_denied()
        for field in ("realGenerationVerified", "cancelAcknowledged", "credentialsReused"):
            with self.subTest(missing=field):
                report = dict(self.report)
                del report[field]
                pilot.private_json(self.verification, report)
                self.assert_gateway_denied()

    def test_report_requires_each_acceptance_flag_to_be_explicitly_true(self):
        for field in ("realGenerationVerified", "cancelAcknowledged", "credentialsReused"):
            for value in (False, None, 0, 1, "true"):
                with self.subTest(field=field, value=value):
                    pilot.private_json(self.verification, dict(self.report, **{field: value}))
                    self.assert_gateway_denied()

    def test_invalid_or_ambiguous_json_fails_closed(self):
        for path, valid in ((self.root / "policy.json", pilot.POLICY),
                            (self.root / "release.json", self.receipt), (self.verification, self.report)):
            duplicate = "{" + json.dumps(next(iter(valid))) + ":null," + json.dumps(valid)[1:]
            for payload in ("{", "raw ACP frame\n" + json.dumps(valid),
                            json.dumps(valid) + "\nraw ACP frame", duplicate):
                with self.subTest(path=path.name, payload=payload):
                    path.write_text(payload)
                    self.assert_gateway_denied()
            pilot.private_json(path, valid)

    def test_symlinks_and_shared_permissions_fail_closed(self):
        paths = [self.root, self.root / "pilot.lock", self.root / "bin", self.binary,
                 self.root / "home", self.root / "reports", self.root / "work",
                 self.root / "home/.gemini", self.root / "home/.gemini/antigravity-cli",
                 self.root / "policy.json", self.root / "release.json", self.verification,
                 self.root / "home/.gemini/antigravity-cli/settings.json"]
        for path in paths:
            with self.subTest(path=path, kind="mode"):
                original_mode = stat.S_IMODE(path.stat().st_mode)
                path.chmod(original_mode | 0o044)
                try:
                    self.assert_gateway_denied()
                finally:
                    path.chmod(original_mode)
            with self.subTest(path=path, kind="symlink"):
                saved = Path(self.temporary.name) / "saved"
                path.rename(saved)
                path.symlink_to(saved, target_is_directory=saved.is_dir())
                try:
                    self.assert_gateway_denied()
                finally:
                    path.unlink()
                    saved.rename(path)

    def test_foreign_owner_is_rejected(self):
        with patch.object(pilot.os, "getuid", return_value=os.getuid() + 1):
            self.assert_gateway_denied()

    def test_invalid_models_and_relative_state_fail_without_mutation(self):
        before = self.snapshot()
        for model in ("", " ", "a" * 161, "model\n", "auto*", "model;command", "model --login"):
            with self.subTest(model=model), self.assertRaises(pilot.PilotError):
                self.gateway(model)
        with self.assertRaises(pilot.PilotError):
            self.invoke("gateway-agent", "--model", self.model, state=Path("relative-state"))
        self.agent.assert_not_called()
        self.assertEqual(before, self.snapshot())

    def test_gateway_does_not_accept_arbitrary_command_arguments(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
            self.invoke("gateway-agent", "--model", self.model, "--", "acp", "--login")
        self.assertEqual(2, failure.exception.code)
        self.agent.assert_not_called()

    def test_existing_lock_conflict_prevents_gateway_login_and_verify(self):
        before = self.snapshot()
        with pilot.pilot_lock(self.root, existing=True):
            for arguments in (("gateway-agent", "--model", self.model), ("login",), ("verify", "--model", self.model)):
                with self.subTest(action=arguments[0]), self.assertRaisesRegex(pilot.PilotError, "already running"):
                    self.invoke(*arguments)
        self.agent.assert_not_called()
        self.installer.assert_not_called()
        self.assertEqual(before, self.snapshot())

    def test_gateway_holds_lock_until_agent_exit_and_releases_it_after_errors(self):
        previous_handler = signal.getsignal(signal.SIGTERM)

        def agent(*arguments):
            with self.assertRaisesRegex(pilot.PilotError, "already running"):
                self.invoke("login")
            raise KeyboardInterrupt()

        self.agent.side_effect = agent
        with self.assertRaises(KeyboardInterrupt):
            self.gateway()
        with pilot.pilot_lock(self.root, existing=True):
            pass
        self.assertEqual(previous_handler, signal.getsignal(signal.SIGTERM))
        self.assertEqual(self.report, json.loads(self.verification.read_text()))

    def test_sigterm_kills_and_reaps_child_while_lock_is_held(self):
        previous_handler = signal.getsignal(signal.SIGTERM)
        with patch.object(pilot, "run_agent", side_effect=self.real_run_agent), \
                patch.object(pilot, "bridge", return_value=contextlib.nullcontext()), \
                patch.object(pilot.subprocess, "Popen") as popen, patch.object(pilot.os, "killpg") as kill_group:
            process = popen.return_value.__enter__.return_value
            process.pid = 42000123

            def communicate(timeout=None):
                with self.assertRaisesRegex(pilot.PilotError, "already running"):
                    with pilot.pilot_lock(self.root, existing=True):
                        pass
                if process.communicate.call_count == 1:
                    signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
                return None, None

            def kill(process_id, signum):
                with self.assertRaisesRegex(pilot.PilotError, "already running"):
                    with pilot.pilot_lock(self.root, existing=True):
                        pass

            process.communicate.side_effect = communicate
            kill_group.side_effect = kill
            with self.assertRaisesRegex(pilot.PilotError, "interrupted"):
                self.gateway()
            self.assertEqual([unittest.mock.call.communicate(timeout=None), unittest.mock.call.communicate(timeout=5)], process.mock_calls)
            kill_group.assert_called_once_with(process.pid, signal.SIGKILL)
            command = popen.call_args.args[0]
            self.assertEqual("/usr/bin/bwrap", command[0])
            self.assertNotIn("--login", command)
            self.assertEqual("--ro-bind", command[command.index(str(self.root / "policy.json")) - 1])
            self.assertEqual({"env": {}, "start_new_session": True}, popen.call_args.kwargs)
        with pilot.pilot_lock(self.root, existing=True):
            pass
        self.assertEqual(previous_handler, signal.getsignal(signal.SIGTERM))
        self.assertEqual(self.report, json.loads(self.verification.read_text()))
        self.assertEqual("", self.stdout.getvalue())

    def test_login_invalidates_acceptance_before_reauthentication(self):
        def login(root, proxy, online, arguments):
            self.assertFalse(self.verification.exists())
            self.assertEqual(["acp", "--login"], arguments)
            return 1

        self.agent.side_effect = login
        with patch.object(pilot, "install_release", return_value=None):
            self.assertEqual(1, self.invoke("login"))
        self.assertFalse(self.verification.exists())
        self.agent.assert_called_once_with(self.root, self.proxy, True, ["acp", "--login"])
        self.assertEqual(b"mock credential must not be read by wrapper", self.credentials.read_bytes())

    def test_login_and_verify_invalidate_even_if_cli_check_fails(self):
        for arguments in (("login",), ("verify", "--model", self.model)):
            with self.subTest(action=arguments[0]):
                pilot.private_json(self.verification, self.report)
                with patch.object(pilot, "install_release", side_effect=pilot.PilotError("CLI failed")):
                    with self.assertRaises(pilot.PilotError):
                        self.invoke(*arguments)
                self.assertFalse(self.verification.exists())
        self.agent.assert_not_called()

    def test_verify_invalidates_acceptance_before_starting_checks(self):
        def checks(*arguments):
            self.assertFalse(self.verification.exists())
            raise pilot.PilotError("acceptance failed")

        with patch.object(pilot, "install_release", return_value=None), patch.object(pilot, "run_checks", side_effect=checks):
            with self.assertRaises(pilot.PilotError):
                self.invoke("verify", "--model", self.model)
        self.assertFalse(self.verification.exists())

    def test_checks_remove_old_pass_before_missing_jar_or_invalid_output(self):
        with self.assertRaises(pilot.PilotError):
            pilot.run_checks(self.root, self.proxy, "verify", self.model)
        self.assertFalse(self.verification.exists())
        jar = self.repo / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
        jar.parent.mkdir(parents=True)
        jar.touch()
        results = [SimpleNamespace(stdout="raw ACP frame", returncode=1),
                   SimpleNamespace(stdout=json.dumps(self.report), returncode=1)]
        for result in results:
            with self.subTest(result=result):
                pilot.private_json(self.verification, self.report)
                with patch.object(pilot, "run_owned_process", return_value=result), self.assertRaises(pilot.PilotError):
                    pilot.run_checks(self.root, self.proxy, "verify", self.model)
                self.assertFalse(self.verification.exists())

    def test_failed_verification_replaces_pass_with_failure_report(self):
        jar = self.repo / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
        jar.parent.mkdir(parents=True)
        jar.touch()
        failure_report = {"status": "AUTHENTICATION_REQUIRED_OR_DENIED", "realGenerationVerified": False}

        def checks(*arguments, **keywords):
            self.assertFalse(self.verification.exists())
            return SimpleNamespace(stdout=json.dumps(failure_report), returncode=10)

        with patch.object(pilot, "run_owned_process", side_effect=checks), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(10, pilot.run_checks(self.root, self.proxy, "verify", self.model))
        self.assertEqual(failure_report, json.loads(self.verification.read_text()))
        self.assert_gateway_denied()


class PilotProcessOwnershipTest(unittest.TestCase):
    def test_checks_reap_before_unlock_on_timeout_interrupt_and_other_exceptions(self):
        failures = (subprocess.TimeoutExpired("private-command", 1), KeyboardInterrupt(), RuntimeError("fixture"),
                    OSError("fixture"), SystemExit(2))
        for mode in ("verify", "probe"):
            for failure in failures:
                with self.subTest(mode=mode, failure=type(failure).__name__), tempfile.TemporaryDirectory() as name:
                    root = Path(name)
                    pilot.prepare_files(root)
                    report = root / "reports" / ("verification.json" if mode == "verify" else "probe.json")
                    pilot.private_json(report, {"status": "STALE_SUCCESS"})
                    jar = root / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
                    jar.parent.mkdir(parents=True)
                    jar.touch()
                    with patch.object(pilot, "REPO", root), patch.object(pilot.subprocess, "Popen") as factory, \
                            patch.object(pilot.os, "killpg") as kill_group:
                        process = factory.return_value.__enter__.return_value
                        process.pid = 42000123

                        def communicate(timeout=None):
                            self.assertFalse(report.exists())
                            with self.assertRaisesRegex(pilot.PilotError, "already running"):
                                with pilot.pilot_lock(root):
                                    pass
                            if process.communicate.call_count == 1:
                                raise failure
                            return "", None

                        process.communicate.side_effect = communicate
                        expected = pilot.PilotError if isinstance(failure, subprocess.TimeoutExpired) else type(failure)
                        with pilot.pilot_lock(root), self.assertRaises(expected):
                            pilot.run_checks(root, "http://127.0.0.1:7897", mode, "gemini-fixed")
                        kill_group.assert_called_once_with(process.pid, signal.SIGTERM)
                        self.assertTrue(factory.call_args.kwargs["start_new_session"])
                        self.assertEqual(1, factory.call_count)
                    self.assertFalse(report.exists())
                    with pilot.pilot_lock(root):
                        pass

    def test_success_and_nonzero_exit_also_drain_remaining_owned_group_members(self):
        for returncode in (0, 10):
            with self.subTest(returncode=returncode), patch.object(pilot.subprocess, "Popen") as factory, \
                    patch.object(pilot.os, "killpg") as kill_group, \
                    patch.object(pilot, "owned_group_running", side_effect=[True, True, False]), \
                    patch.object(pilot.time, "sleep"):
                process = factory.return_value.__enter__.return_value
                process.pid = 42000123
                process.returncode = returncode
                process.communicate.return_value = ("sanitized", None)
                result = pilot.run_owned_process(["synthetic-java"], stdout=subprocess.PIPE)
                self.assertEqual(returncode, result.returncode)
                self.assertEqual("sanitized", result.stdout)
                kill_group.assert_called_once_with(process.pid, signal.SIGTERM)
                self.assertEqual([None, 5], [call.kwargs["timeout"] for call in process.communicate.call_args_list])

    def test_cleanup_waits_for_and_escalates_group_members_after_the_leader_has_exited(self):
        process = unittest.mock.Mock(pid=42000123)
        process.communicate.return_value = ("", None)
        with patch.object(pilot.os, "killpg") as kill_group, \
                patch.object(pilot, "owned_group_running", side_effect=[True, True, False]), \
                patch.object(pilot.time, "monotonic", side_effect=[0, 6]), patch.object(pilot.time, "sleep"):
            pilot.stop_owned_process(process)
        self.assertEqual([(process.pid, signal.SIGTERM), (process.pid, signal.SIGKILL)],
                         [call.args for call in kill_group.call_args_list])

    def test_real_sigterm_verify_probe_and_prepare_reap_java_agent_and_detached_sandbox_before_unlock(self):
        sandbox_source = textwrap.dedent("""
            import os, pathlib, sys, time
            root = pathlib.Path(sys.argv[1])
            (root / 'sandbox-ready').write_text(str(os.getpid()))
            while True:
                time.sleep(1)
        """)
        agent_source = textwrap.dedent("""
            from contextlib import nullcontext
            from pathlib import Path
            import fcntl, os, signal, sys, time
            sys.path.insert(0, sys.argv[1])
            import antigravity_acp_pilot as pilot
            root = Path(sys.argv[2])
            sandbox_source = sys.argv[3]
            pilot.bridge = lambda *args, **kwargs: nullcontext()
            pilot.sandbox_command = lambda *args, **kwargs: [sys.executable, '-c', sandbox_source, str(root)]
            def original_handler(signum, frame):
                raise RuntimeError('Original handler must be restored, not used')
            signal.signal(signal.SIGTERM, original_handler)
            (root / 'agent-ready').write_text(str(os.getpid()))
            sys.argv = ['pilot', '--state', str(root), 'agent', '--online', '--', 'acp']
            try:
                pilot.main()
            except KeyboardInterrupt:
                sandbox_pid = int((root / 'sandbox-ready').read_text())
                try:
                    os.kill(sandbox_pid, 0)
                    raise AssertionError('Sandbox was not reaped')
                except ProcessLookupError:
                    pass
                time.sleep(0.2)
                with (root / 'pilot.lock').open('r+') as lock:
                    try:
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        raise AssertionError('Lock released before agent cleanup')
                    except BlockingIOError:
                        pass
                assert signal.getsignal(signal.SIGTERM) is original_handler
                (root / 'agent-reaped-sandbox').touch()
        """)
        java_source = textwrap.dedent("""
            from pathlib import Path
            import os, signal, subprocess, sys
            root = Path(sys.argv[2])
            signal.signal(signal.SIGTERM, lambda signum, frame: os._exit(0))
            (root / 'java-ready').write_text(str(os.getpid()))
            with subprocess.Popen([sys.executable, '-c', sys.argv[3], sys.argv[1], str(root), sys.argv[4]],
                                  stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) as agent:
                agent.wait()
        """)
        controller_source = textwrap.dedent("""
            from pathlib import Path
            import ctypes, os, signal, subprocess, sys
            sys.path.insert(0, sys.argv[1])
            import antigravity_acp_pilot as pilot
            scripts_directory = sys.argv[1]
            root = Path(sys.argv[2])
            assert ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) == 0
            action, java_source, agent_source, sandbox_source = sys.argv[3:]
            original_popen = subprocess.Popen
            def synthetic_java(command, **kwargs):
                assert pilot.MAIN_CLASS in command
                return original_popen([sys.executable, '-c', java_source, scripts_directory, str(root),
                                       agent_source, sandbox_source], **kwargs)
            def original_handler(signum, frame):
                raise RuntimeError('Original handler must be restored, not used')
            signal.signal(signal.SIGTERM, original_handler)
            pilot.REPO = root
            pilot.install_release = lambda *args: None
            pilot.check_sandbox = lambda *args: None
            pilot.subprocess.Popen = synthetic_java
            real_run_owned = pilot.run_owned_process
            def owned_process(command, **kwargs):
                if '--batch-mode' in command:
                    return subprocess.CompletedProcess(command, 0)
                return real_run_owned(command, **kwargs)
            pilot.run_owned_process = owned_process
            sys.argv = ['pilot', '--state', str(root), action]
            if action == 'verify':
                sys.argv += ['--model', 'gemini-fixed']
            try:
                pilot.main()
            except KeyboardInterrupt:
                assert (root / 'agent-reaped-sandbox').is_file()
                agent_pid = int((root / 'agent-ready').read_text())
                assert os.waitpid(agent_pid, 0)[1] == 0
                assert signal.getsignal(signal.SIGTERM) is original_handler
                (root / 'controller-cancelled').touch()
        """)
        for action in ("verify", "probe", "prepare"):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                pilot.prepare_files(root)
                (root / "pilot.lock").touch(mode=0o600)
                report = root / "reports" / ("verification.json" if action == "verify" else "probe.json")
                pilot.private_json(report, {"status": "STALE_SUCCESS"})
                pilot.private_json(root / "reports/ready.json", {"status": "STALE_READY"})
                jar = root / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
                jar.parent.mkdir(parents=True)
                jar.touch()
                command = [sys.executable, "-c", controller_source, str(Path(__file__).resolve().parent),
                           str(root), action, java_source, agent_source, sandbox_source]
                with subprocess.Popen(command, env={"PYTHONDONTWRITEBYTECODE": "1"}, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE) as controller:
                    try:
                        deadline = time.monotonic() + 5
                        while (not (root / "sandbox-ready").exists() or not (root / "sandbox-ready").read_text()) \
                                and controller.poll() is None and time.monotonic() < deadline:
                            time.sleep(0.01)
                        if not (root / "sandbox-ready").exists() and controller.poll() is not None:
                            _, errors = controller.communicate(timeout=1)
                            self.fail("Synthetic chain did not start: " + errors.decode())
                        self.assertTrue((root / "sandbox-ready").exists(), "Synthetic chain did not start")
                        process_ids = [int((root / marker).read_text())
                                       for marker in ("java-ready", "agent-ready", "sandbox-ready")]
                        controller.send_signal(signal.SIGTERM)
                        _, errors = controller.communicate(timeout=8)
                        self.assertEqual(0, controller.returncode, errors.decode())
                        self.assertTrue((root / "controller-cancelled").is_file())
                        self.assertFalse(report.exists())
                        if action == "prepare":
                            self.assertFalse((root / "reports/ready.json").exists())
                        for process_id in process_ids:
                            with self.assertRaises(ProcessLookupError):
                                os.kill(process_id, 0)
                        with pilot.pilot_lock(root, existing=True):
                            pass
                    finally:
                        for marker in ("java-ready", "sandbox-ready"):
                            if (root / marker).is_file() and (root / marker).read_text():
                                with contextlib.suppress(ProcessLookupError):
                                    os.killpg(int((root / marker).read_text()), signal.SIGKILL)
                        if controller.poll() is None:
                            controller.kill()
                            controller.wait()


if __name__ == "__main__":
    unittest.main()

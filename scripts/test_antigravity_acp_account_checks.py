from contextlib import nullcontext
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import subprocess
import textwrap
import time
import unittest
from unittest.mock import Mock, patch

import antigravity_acp_account_checks as checks
import antigravity_acp_pilot as pilot


class OfficialAccountEvidenceTest(unittest.TestCase):
    def result(self, response="Plan: Google AI Pro", turns=0, error="", usage=None):
        return json.dumps({"response": response, "error": error, "num_turns": turns,
                           "usage": usage if usage is not None else {"input_tokens": 0, "output_tokens": 0}}).encode()

    def session(self, tier="PRO"):
        value = {"status": "OFFICIAL_ACP_ACCOUNT_STATUS_VERIFIED", "modelId": "gemini-fixed", "currentModelMatches": True,
                 "generationRequests": 0, "modelSwitches": 0, "fallbacks": 0, "realGenerationVerified": False,
                 "subscriptionEvidence": "PLAN_NOT_EXPOSED_BY_OFFICIAL_ACP_SESSION" if tier is None else "OFFICIAL_ACP_SESSION_TIER_METADATA"}
        if tier is not None:
            value["subscriptionTier"] = tier
        return value

    def test_current_official_acp_session_pro_tier_is_evidence_without_borrowing_browser_identity(self):
        initial = checks.summarize_status(self.result("Remaining quota: 99%"), 0, {})
        session = self.session()
        session.update(email="private@example.invalid", token="private-token", refreshAfterExpiryVerified=True,
                       oauthTokenEndpointConnections=1, expiry_date=1)
        report = checks.merge_session_evidence(initial, session, "gemini-fixed")
        self.assertTrue(report["subscriptionEntitlementVerified"])
        self.assertEqual("PRO", report["subscriptionTier"])
        self.assertEqual("OFFICIAL_ACP_SESSION_TIER_METADATA", report["subscriptionEvidence"])
        self.assertTrue(report["officialAcpSessionVerified"])
        self.assertTrue(report["canContinueAcceptance"])
        self.assertFalse(report["refreshAfterExpiryVerified"])
        self.assertFalse(report["naturalExpiryObservationStarted"])
        self.assertNotIn("private", json.dumps(report))
        self.assertFalse(initial["subscriptionEntitlementVerified"])

    def test_free_unknown_or_missing_session_tiers_never_verify_paid_entitlement(self):
        initial = checks.summarize_status(self.result("Remaining quota: 99%"), 0, {})
        for tier in ("FREE", "unknown", None, {"private": "Pro"}):
            with self.subTest(tier=tier):
                report = checks.merge_session_evidence(initial, self.session(tier), "gemini-fixed")
                self.assertFalse(report["subscriptionEntitlementVerified"])

    def test_conflicting_official_tier_evidence_stops_acceptance(self):
        initial = checks.summarize_status(self.result(), 0, {})
        report = checks.merge_session_evidence(initial, self.session("FREE"), "gemini-fixed")
        self.assertEqual("OFFICIAL_SUBSCRIPTION_EVIDENCE_CONFLICT", report["status"])
        self.assertFalse(report["canContinueAcceptance"])
        self.assertFalse(report["subscriptionEntitlementVerified"])

    def test_generated_switched_or_malformed_session_results_cannot_verify_tier(self):
        initial = checks.summarize_status(self.result(), 0, {})
        for update in ({"generationRequests": 1}, {"generationRequests": False}, {"modelSwitches": 1}, {"fallbacks": 1},
                       {"currentModelMatches": False}, {"modelId": "another-model"}, {"status": {}},
                       {"realGenerationVerified": True}, {"subscriptionEvidence": "private-secret"}):
            with self.subTest(update=update):
                session = {**self.session(), **update}
                report = checks.merge_session_evidence(initial, session, "gemini-fixed")
                self.assertFalse(report["canContinueAcceptance"])
                self.assertFalse(report["subscriptionEntitlementVerified"])
                self.assertNotIn("private-secret", json.dumps(report))

    def test_quota_or_disabled_status_never_starts_another_official_session(self):
        for error in ("RESOURCE_EXHAUSTED", "This service has been disabled in this account"):
            with self.subTest(error=error), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                (root / "reports").mkdir(mode=0o700)
                (root / "pilot.lock").touch(mode=0o600)
                status = checks.summarize_status(self.result(error=error), 1, {})
                with patch.object(pilot, "validate_gateway_state"), patch.object(checks, "observe_official_status", return_value=status), \
                        patch.object(checks, "observe_official_session") as session:
                    report = checks.check_account(root, "http://127.0.0.1:7897", "gemini-fixed")
                session.assert_not_called()
                self.assertFalse(report["canContinueAcceptance"])

    def test_multiple_official_plan_lines_are_not_accepted_as_a_single_paid_tier(self):
        report = checks.summarize_status(self.result("Plan: Pro" + chr(10) + "Plan: Free"), 0, {})
        self.assertFalse(report["subscriptionEntitlementVerified"])

    def test_zero_remaining_quota_stops_even_when_the_official_command_succeeds(self):
        for text in ("Limit Remaining 0%", "Remaining quota: 0.0 %", "Remaining 0%"):
            with self.subTest(text=text):
                report = checks.summarize_status(self.result(text), 0, {})
                self.assertEqual("QUOTA_EXHAUSTED", report["status"])
                self.assertFalse(report["canContinueAcceptance"])
        self.assertTrue(checks.summarize_status(self.result("Limit Remaining 100%"), 0, {})["canContinueAcceptance"])

    def test_session_quota_failure_does_not_keep_an_earlier_paid_tier_proof(self):
        initial = checks.summarize_status(self.result(), 0, {})
        report = checks.merge_session_evidence(initial, {"status": "QUOTA_EXHAUSTED", "token": "private-token"}, "gemini-fixed")
        self.assertEqual("QUOTA_EXHAUSTED", report["status"])
        self.assertFalse(report["canContinueAcceptance"])
        self.assertFalse(report["subscriptionEntitlementVerified"])
        self.assertNotIn("private-token", json.dumps(report))

    def test_official_session_runner_has_clean_environment_and_no_model_selection_argument(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            jar = root / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
            jar.parent.mkdir(parents=True)
            jar.touch()
            with patch.object(pilot, "REPO", root), patch.object(checks.shutil, "which", return_value="/usr/bin/java"), \
                    patch.object(pilot, "bridge", return_value=nullcontext()), patch.object(checks.subprocess, "Popen") as factory:
                process = factory.return_value.__enter__.return_value
                process.communicate.return_value = (json.dumps(self.session()).encode(), None)
                process.returncode = 0
                report = checks.observe_official_session(root, "http://127.0.0.1:7897", "gemini-fixed")
            self.assertEqual("PRO", report["subscriptionTier"])
            command = factory.call_args.args[0]
            self.assertIn("account-status", command)
            self.assertNotIn("--model", command)
            self.assertEqual({}, factory.call_args.kwargs["env"])
            self.assertTrue(factory.call_args.kwargs["start_new_session"])

    def test_official_session_timeout_stops_only_its_owned_process_group_without_retry(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            jar = root / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
            jar.parent.mkdir(parents=True)
            jar.touch()
            with patch.object(pilot, "REPO", root), patch.object(checks.shutil, "which", return_value="/usr/bin/java"), \
                    patch.object(pilot, "bridge", return_value=nullcontext()), patch.object(checks.subprocess, "Popen") as factory, \
                    patch.object(checks.os, "killpg") as kill:
                process = factory.return_value.__enter__.return_value
                process.pid = 42000123
                process.communicate.side_effect = [subprocess.TimeoutExpired("private-command", 80), (b"", None)]
                with self.assertRaises(pilot.PilotError) as error:
                    checks.observe_official_session(root, "http://127.0.0.1:7897", "gemini-fixed")
                kill.assert_called_once_with(42000123, checks.signal.SIGTERM)
            self.assertEqual(1, factory.call_count)
            self.assertNotIn("private-command", str(error.exception))

    def test_cancellation_and_communication_errors_stop_and_reap_the_owned_group(self):
        for failure in (KeyboardInterrupt(), RuntimeError("private-detail"), OSError("private-detail")):
            with self.subTest(failure=type(failure).__name__), patch.object(checks.os, "killpg") as kill:
                process = Mock(pid=42000123)
                process.communicate.side_effect = [failure, (b"", None)]
                with self.assertRaises(type(failure)) as error:
                    checks.communicate_owned_process(process, 45, "timeout")
                self.assertIs(failure, error.exception)
                kill.assert_called_once_with(42000123, signal.SIGTERM)
                self.assertEqual([45, 5], [call.kwargs["timeout"] for call in process.communicate.call_args_list])

    def test_cancellation_escalates_only_the_owned_group_when_term_is_ignored(self):
        with patch.object(checks.os, "killpg") as kill:
            process = Mock(pid=42000123)
            process.communicate.side_effect = [KeyboardInterrupt(), subprocess.TimeoutExpired("private", 5), (b"", None)]
            with self.assertRaises(KeyboardInterrupt):
                checks.communicate_owned_process(process, 45, "timeout")
            self.assertEqual([(42000123, signal.SIGTERM), (42000123, signal.SIGKILL)],
                             [call.args for call in kill.call_args_list])
            self.assertEqual({}, process.communicate.call_args_list[-1].kwargs)

    def test_process_exit_race_does_not_skip_reaping_or_replace_cancellation(self):
        with patch.object(checks.os, "killpg", side_effect=ProcessLookupError):
            process = Mock(pid=42000123)
            process.communicate.side_effect = [KeyboardInterrupt(), (b"", None)]
            with self.assertRaises(KeyboardInterrupt):
                checks.communicate_owned_process(process, 45, "timeout")
            self.assertEqual(2, process.communicate.call_count)

    def test_status_timeout_uses_a_clean_owned_session_and_never_retries(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            with patch.object(pilot, "bridge", return_value=nullcontext()), \
                    patch.object(pilot, "sandbox_command", return_value=["fake-agent"]), \
                    patch.object(checks.subprocess, "Popen") as factory, patch.object(checks.os, "killpg") as kill:
                process = factory.return_value.__enter__.return_value
                process.pid = 42000123
                process.communicate.side_effect = [subprocess.TimeoutExpired("private", 45), (b"", None)]
                with self.assertRaisesRegex(pilot.PilotError, "^Official read-only status timed out; no retry was made$"):
                    checks.observe_official_status(root, "http://127.0.0.1:7897")
                self.assertTrue(factory.call_args.kwargs["start_new_session"])
                self.assertEqual({}, factory.call_args.kwargs["env"])
                self.assertEqual(1, factory.call_count)
                kill.assert_called_once_with(42000123, signal.SIGTERM)

    def test_real_sigterm_reaps_each_observer_before_releasing_the_pilot_lock(self):
        child_source = textwrap.dedent("""
            import fcntl, os, pathlib, signal, sys, time
            root = pathlib.Path(sys.argv[1])
            def stopped(signum, frame):
                with (root / 'pilot.lock').open('r+') as lock:
                    try:
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        state = 'released-too-early'
                    except BlockingIOError:
                        state = 'still-locked'
                (root / 'child-cleanup').write_text(state)
                raise SystemExit(0)
            signal.signal(signal.SIGTERM, stopped)
            (root / 'child-ready').write_text(str(os.getpid()))
            while True:
                time.sleep(1)
        """)
        controller_source = textwrap.dedent("""
            from contextlib import nullcontext
            from pathlib import Path
            import signal, subprocess, sys
            sys.path.insert(0, sys.argv[1])
            import antigravity_acp_account_checks as checks
            import antigravity_acp_pilot as pilot
            root = Path(sys.argv[2])
            original_popen = subprocess.Popen
            def fake_agent(command, **kwargs):
                return original_popen([sys.executable, '-c', sys.argv[4], str(root)], **kwargs)
            def interrupted(signum, frame):
                raise KeyboardInterrupt
            signal.signal(signal.SIGTERM, interrupted)
            pilot.REPO = root
            pilot.bridge = lambda *args, **kwargs: nullcontext()
            pilot.sandbox_command = lambda *args: ['fake-agent']
            checks.shutil.which = lambda name: sys.executable
            checks.subprocess.Popen = fake_agent
            try:
                with pilot.pilot_lock(root, existing=True):
                    if sys.argv[3] == 'session':
                        checks.observe_official_session(root, 'http://127.0.0.1:7897', 'gemini-fixed')
                    else:
                        checks.observe_official_status(root, 'http://127.0.0.1:7897')
            except KeyboardInterrupt:
                (root / 'controller-cancelled').touch()
        """)
        for mode in ("status", "session"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                (root / "pilot.lock").touch(mode=0o600)
                jar = root / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
                jar.parent.mkdir(parents=True)
                jar.touch()
                child_pid = None
                with subprocess.Popen([sys.executable, "-c", controller_source, str(Path(__file__).resolve().parent),
                                       str(root), mode, child_source], env={"PYTHONDONTWRITEBYTECODE": "1"},
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE) as controller:
                    try:
                        deadline = time.monotonic() + 5
                        while not (root / "child-ready").exists() and controller.poll() is None and time.monotonic() < deadline:
                            time.sleep(0.01)
                        self.assertTrue((root / "child-ready").exists(), "Synthetic observer did not become ready")
                        child_pid = int((root / "child-ready").read_text())
                        controller.send_signal(signal.SIGTERM)
                        _, errors = controller.communicate(timeout=8)
                        self.assertEqual(0, controller.returncode, errors.decode())
                        self.assertTrue((root / "controller-cancelled").is_file())
                        self.assertEqual("still-locked", (root / "child-cleanup").read_text())
                        with self.assertRaises(ProcessLookupError):
                            os.kill(child_pid, 0)
                        with pilot.pilot_lock(root, existing=True):
                            pass
                    finally:
                        if child_pid is not None:
                            try:
                                os.killpg(child_pid, signal.SIGKILL)
                            except ProcessLookupError:
                                pass
                        if controller.poll() is None:
                            controller.kill()
                            controller.wait()

    def test_account_cancellation_invalidates_the_pending_report_before_unlock(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            (root / "reports").mkdir(mode=0o700)
            (root / "pilot.lock").touch(mode=0o600)
            with patch.object(pilot, "validate_gateway_state"), \
                    patch.object(checks, "observe_official_status", side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    checks.check_account(root, "http://127.0.0.1:7897", "gemini-fixed")
            report = json.loads((root / "reports/account-verification.json").read_text())
            self.assertEqual("ACCOUNT_EVIDENCE_CHECK_STOPPED", report["status"])
            self.assertFalse(report["canContinueAcceptance"])
            with pilot.pilot_lock(root, existing=True):
                pass

    def test_official_readonly_plan_is_evidence_but_token_connection_is_not_expiry_evidence(self):
        report = checks.summarize_status(self.result(), 0, {"oauth2.googleapis.com": 1})
        self.assertTrue(report["subscriptionEntitlementVerified"])
        self.assertEqual("PRO", report["subscriptionTier"])
        self.assertTrue(report["oauthTokenEndpointObserved"])
        self.assertFalse(report["refreshAfterExpiryVerified"])

    def test_free_unknown_or_generated_plan_never_proves_a_paid_subscription(self):
        for value in (self.result("Plan: Free"), self.result("Plan: unknown"), self.result(turns=1),
                      self.result(usage={"output_tokens": 1}), self.result(usage={"total_tokens": False}),
                      self.result("The model answered: Plan: Google AI Pro")):
            with self.subTest(value=value):
                self.assertFalse(checks.summarize_status(value, 0, {})["subscriptionEntitlementVerified"])

    def test_quota_service_disabled_and_auth_errors_stop_further_acceptance(self):
        for error, status in (("RESOURCE_EXHAUSTED private-secret", "QUOTA_EXHAUSTED"),
                              ("This service has been disabled in this account", "SERVICE_DISABLED"),
                              ("authentication failed private-secret", "OFFICIAL_STATUS_NOT_VERIFIED")):
            report = checks.summarize_status(self.result(error=error), 1, {})
            self.assertEqual(status, report["status"])
            self.assertFalse(report["canContinueAcceptance"])
            self.assertNotIn("private-secret", json.dumps(report))

    def test_unstructured_and_oversized_results_are_rejected(self):
        for output in (b'[]', b'not json', b'x' * 524_289, b'{"response": {}}', b'{"num_turns":1,"num_turns":0}'):
            with self.assertRaises(pilot.PilotError):
                checks.summarize_status(output, 0, {})

    def test_account_identity_and_credential_like_fields_are_not_exported(self):
        value = json.loads(self.result())
        value.update(email="private@example.invalid", refresh_token="private-refresh-token",
                     expiry_date=1, client_secret="private-client-secret")
        report = checks.summarize_status(json.dumps(value).encode(), 0, {})
        self.assertNotIn("private", json.dumps(report))
        self.assertFalse(report["credentialFilesRead"])
        self.assertFalse(report["tokenEndpointBodiesInspected"])
        self.assertFalse(report["refreshAfterExpiryVerified"])

    def test_failed_preflight_invalidates_an_earlier_success_without_exposing_failure_details(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            (root / "reports").mkdir(mode=0o700)
            (root / "pilot.lock").touch(mode=0o600)
            destination = root / "reports/account-verification.json"
            pilot.private_json(destination, {"status": "OFFICIAL_READ_ONLY_STATUS_VERIFIED", "canContinueAcceptance": True})
            with patch.object(pilot, "validate_gateway_state", side_effect=pilot.PilotError("private-detail")):
                with self.assertRaises(pilot.PilotError):
                    checks.check_account(root, "http://127.0.0.1:7897", "gemini-fixed")
            report = json.loads(destination.read_text())
            self.assertEqual("ACCOUNT_EVIDENCE_CHECK_STOPPED", report["status"])
            self.assertFalse(report["canContinueAcceptance"])
            self.assertNotIn("private-detail", json.dumps(report))


if __name__ == "__main__":
    unittest.main()

import json
import hashlib
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlencode

import antigravity_acp_pilot as pilot
import antigravity_agy_admin as admin


def login_url(**changes):
    query = {"client_id": "fixture.apps.googleusercontent.com", "response_type": "code",
             "redirect_uri": "https://antigravity.google/oauth-callback", "state": "fixture-state"}
    query.update(changes)
    return "https://accounts.google.com/o/oauth2/v2/auth?" + urlencode(query)


class AgyAdminAuthorizationTest(unittest.TestCase):
    def test_prepare_never_starts_login_or_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / ".runtime"
            with patch.object(sys, "argv", ["admin", "--state", str(state), "--proxy", "http://127.0.0.1:7897", "--prepare"]), \
                    patch.object(pilot, "install_release") as install, patch.object(pilot, "check_sandbox") as sandbox, \
                    patch.object(pilot, "run_agent", side_effect=AssertionError("No login or generation")), \
                    patch.object(admin, "emit") as emit:
                self.assertEqual(0, admin.main())
            install.assert_called_once_with(state, "http://127.0.0.1:7897", None, None)
            sandbox.assert_called_once_with(state, "http://127.0.0.1:7897", None, None)
            self.assertEqual("ready", emit.call_args.args[0])

    def test_new_account_reuses_only_verified_cli_not_cached_home(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / ".runtime"
            account = Path(directory) / "account"
            for root in (cache, account):
                pilot.prepare_files(root)
            binary = b"verified-cli-fixture"
            (cache / "bin/agy").write_bytes(binary)
            (cache / "bin/agy").chmod(0o700)
            pilot.private_json(cache / "release.json", {"release": pilot.pinned_release(), "binarySha512": hashlib.sha512(binary).hexdigest()})
            (cache / "home/private-credential").write_text("not-an-account-credential")
            with patch.object(pilot, "install_release", side_effect=AssertionError("No repeated download")):
                admin.install_account_release(account, "http://127.0.0.1:7897")
            self.assertEqual(binary, (account / "bin/agy").read_bytes())
            self.assertFalse((account / "home/private-credential").exists())
            (cache / "bin/agy").write_bytes(b"tampered")
            with self.assertRaises(pilot.PilotError):
                admin.install_account_release(account, "http://127.0.0.1:7897")

    def test_accepts_only_official_cli_google_code_flow(self):
        value = login_url()
        self.assertEqual(value, admin.authorization_url(value))
        for value in (value.replace("accounts.google.com", "accounts.google.com.evil.invalid"),
                      value.replace("https://accounts", "http://accounts"),
                      value.replace("https://accounts", "https://user@accounts"),
                      value + "&state=duplicate", value + "#fragment",
                      login_url(response_type="token"), login_url(client_id="not-a-google-client"),
                      login_url(redirect_uri="http://localhost:8085/callback"),
                      login_url(redirect_uri="https://evil.invalid/oauth-callback")):
            with self.subTest(value=value):
                self.assertIsNone(admin.authorization_url(value))

    def test_code_input_cannot_inject_terminal_keys_or_commands(self):
        self.assertEqual("4/fixture-code", admin.verification_code("4/fixture-code"))
        for value in ("code\nquit", "code\r", "\x1b[A", "", "code with spaces", "x" * 4097, None):
            with self.subTest(value=value):
                with self.assertRaises(pilot.PilotError):
                    admin.verification_code(value)

    def test_pty_transfers_code_only_to_cli_and_does_not_forward_raw_output(self):
        with tempfile.TemporaryDirectory() as directory:
            driver = Path(directory) / "driver.py"
            driver.write_text(
                "import sys, time\n"
                "print('private-token-value-do-not-forward', flush=True)\n"
                "sys.stdout.write(" + repr(login_url().rsplit("&state=", 1)[0]) + ")\n"
                "sys.stdout.flush()\ntime.sleep(0.1)\n"
                "print('&state=fixture-state', flush=True)\n"
                "assert input() == '4/fixture-code'\n"
                "print('private-session-value', flush=True)\n")
            process = subprocess.Popen([sys.executable, "-c",
                "import antigravity_agy_admin as a; import sys; "
                "a.interactive_login([sys.executable, sys.argv[1]], env={})", str(driver)],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                cwd=Path(__file__).parent, start_new_session=True)
            try:
                first = json.loads(process.stdout.readline())
                self.assertEqual("authorize", first["event"])
                self.assertEqual(login_url(), first["authorizeUrl"])
                process.stdin.write(b'{"code":"4/fixture-code"}\n')
                process.stdin.flush()
                output, errors = process.communicate(timeout=10)
                self.assertEqual(0, process.returncode, errors.decode())
                self.assertEqual(["verifying", "logged_in"], [json.loads(line)["event"] for line in output.splitlines()])
                self.assertNotIn(b"private-", output + errors)
                self.assertNotIn(b"fixture-code", output + errors)
            finally:
                if process.poll() is None:
                    pilot.stop_owned_process(process, signal.SIGKILL)

    def test_login_restores_subscription_only_policy_when_runner_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pilot.prepare_files(root)
            def fail(*args, **kwargs):
                settings = root / "home/.gemini/antigravity-cli/settings.json"
                settings.write_text('{"useG1Credits":true}')
                raise pilot.PilotError("fixture failure")
            with patch.object(pilot, "bridge", return_value=__import__("contextlib").nullcontext()), \
                    patch.object(pilot, "sandbox_command", return_value=["fixture"]):
                with self.assertRaises(pilot.PilotError):
                    pilot.run_agent(root, "http://127.0.0.1:7897", True, ["acp", "--login"], runner=fail)
            self.assertEqual(pilot.POLICY, json.loads((root / "home/.gemini/antigravity-cli/settings.json").read_text()))


if __name__ == "__main__":
    unittest.main()

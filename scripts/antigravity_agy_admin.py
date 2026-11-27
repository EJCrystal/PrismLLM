#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import platform
import pty
import re
import select
import shutil
import signal
import subprocess
import sys
import struct
import termios
import tempfile
import time
from urllib.parse import parse_qsl, urlsplit

import antigravity_acp_pilot as pilot


def authorization_url(value):
    try:
        parsed = urlsplit(value)
        pairs = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
        query = dict(pairs)
        callback = urlsplit(query.get("redirect_uri", ""))
        if (len(value) > 8192 or parsed.scheme != "https" or parsed.netloc != "accounts.google.com" or
                parsed.path not in {"/o/oauth2/v2/auth", "/o/oauth2/auth"} or parsed.fragment or
                len(pairs) != len(query) or query.get("response_type") != "code" or
                not query.get("client_id", "").endswith(".apps.googleusercontent.com") or
                callback.scheme != "https" or callback.netloc != "antigravity.google" or
                callback.path != "/oauth-callback" or callback.query or callback.fragment):
            return None
        return value
    except (ValueError, UnicodeError):
        return None


def verification_code(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._~+/%=-]{1,4096}", value):
        raise pilot.PilotError("INVALID_VERIFICATION_CODE")
    return value


def emit(event, **values):
    print(json.dumps({"event": event, **values}), flush=True)


def interactive_login(command, **arguments):
    master, terminal = pty.openpty()
    settings = termios.tcgetattr(terminal)
    settings[3] &= ~termios.ECHO
    termios.tcsetattr(terminal, termios.TCSANOW, settings)
    import fcntl
    fcntl.ioctl(terminal, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 16384, 0, 0))
    process = None
    pending = b""
    input_buffer = b""
    offered = False
    submitted = False
    deadline = time.monotonic() + 600
    try:
        process = subprocess.Popen(command, stdin=terminal, stdout=terminal, stderr=terminal,
                                   env=arguments.get("env", {}), start_new_session=True)
        os.close(terminal)
        terminal = None
        while time.monotonic() < deadline:
            watched = [master] if submitted else [master, sys.stdin.buffer]
            readable, _, _ = select.select(watched, [], [], 0.2)
            if master in readable:
                try:
                    chunk = os.read(master, 8192)
                except OSError:
                    chunk = b""
                if chunk:
                    pending = (pending + chunk)[-16384:]
                    clean = re.sub(rb"\x1b\[[0-?]*[ -/]*[@-~]", b"", pending).decode("utf-8", "replace")
                    for match in re.finditer(r"https://accounts\.google\.com/[^\s\x00-\x20\x7f<>\"']+(?=[\s\x00-\x20\x7f<>\"'])", clean):
                        candidate = authorization_url(match.group())
                        if candidate and not offered:
                            emit("authorize", authorizeUrl=candidate)
                            offered = True
                elif process.poll() is not None:
                    break
            if sys.stdin.buffer in readable:
                chunk = os.read(sys.stdin.fileno(), 8192)
                if not chunk:
                    raise pilot.PilotError("LOGIN_CANCELLED")
                input_buffer += chunk
                if len(input_buffer) > 8192:
                    raise pilot.PilotError("INVALID_VERIFICATION_CODE")
                if b"\n" in input_buffer:
                    line, input_buffer = input_buffer.split(b"\n", 1)
                    request = json.loads(line)
                    if not offered or submitted or not isinstance(request, dict) or set(request) != {"code"}:
                        raise pilot.PilotError("INVALID_VERIFICATION_CODE")
                    code = verification_code(request["code"])
                    os.write(master, (code + "\n").encode())
                    submitted = True
                    emit("verifying")
            if process.poll() is not None:
                break
        else:
            raise pilot.PilotError("LOGIN_EXPIRED")
        process.wait(timeout=5)
        if process.returncode != 0:
            raise pilot.PilotError("OFFICIAL_CLI_LOGIN_FAILED")
        emit("logged_in")
        return subprocess.CompletedProcess(command, 0)
    finally:
        if process is not None:
            pilot.stop_owned_process(process, signal.SIGTERM)
        os.close(master)
        if terminal is not None:
            os.close(terminal)


def install_account_release(root, proxy):
    cache = root.parent / ".runtime"
    if root.name == ".runtime" or not cache.exists():
        pilot.install_release(root, proxy)
        return
    pilot.require_private_path(cache, directory=True)
    with pilot.pilot_lock(cache):
        release = pilot.pinned_release()
        pilot.verify_installed_release(cache, release)
        with tempfile.TemporaryDirectory(prefix="cached-release-", dir=root) as directory:
            for relative, executable in (("bin/agy", True), ("release.json", False)):
                target = root / relative
                if pilot.existing_private_file(target, executable=executable):
                    continue
                staged = Path(directory) / target.name
                with (cache / relative).open("rb") as source, staged.open("xb") as output:
                    os.fchmod(output.fileno(), 0o700 if executable else 0o600)
                    shutil.copyfileobj(source, output)
                os.link(staged, target)
        pilot.verify_installed_release(root, release)


@pilot.interrupt_on_sigterm()
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--proxy", required=True)
    parser.add_argument("--prepare", action="store_true")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() != "x86_64" or not args.state.is_absolute():
        raise pilot.PilotError("UNSUPPORTED_LOGIN_ENVIRONMENT")
    pilot.proxy_address(args.proxy)
    root = args.state
    os.umask(0o077)
    pilot.private_directory(root)
    with pilot.pilot_lock(root):
        pilot.prepare_files(root)
        pilot.invalidate_verification(root)
        emit("preparing")
        install_account_release(root, args.proxy)
        pilot.check_sandbox(root, args.proxy)
        if args.prepare:
            emit("ready")
            return 0
        pilot.run_agent(root, args.proxy, True, ["acp", "--login"], runner=interactive_login)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (pilot.PilotError, OSError, ValueError, subprocess.SubprocessError, KeyboardInterrupt):
        emit("failed")
        raise SystemExit(1)

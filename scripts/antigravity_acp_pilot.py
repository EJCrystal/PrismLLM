#!/usr/bin/env python3
import argparse
import base64
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import select
import signal
import shutil
import socket
import socketserver
import stat
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.parse
import urllib.request


REPO = Path(__file__).resolve().parent.parent
DEFAULT_STATE = Path.home() / ".local/state/prismllm/antigravity-acp-pilot"
MAIN_CLASS = "dev.soven.prismllm.antigravity.AntigravityAcpPilotMainKt"
ALLOWED_HOSTS = frozenset({
    "accounts.google.com", "oauth2.googleapis.com", "www.googleapis.com",
    "openidconnect.googleapis.com", "cloudcode-pa.googleapis.com",
    "daily-cloudcode-pa.googleapis.com", "staging-cloudcode-pa.googleapis.com",
})
POLICY = {
    "useG1Credits": False,
    "modelProvider": "",
    "toolPermission": "strict",
    "permissions": {"allow": [], "ask": [], "deny": [
        "command(*)", "read_file(*)", "write_file(*)", "read_url(*)",
        "execute_url(*)", "download_url(*)", "mcp(*)",
    ]},
    "historySize": 0,
    "showTips": False,
    "showFeedbackSurvey": False,
}


class PilotError(Exception):
    pass


@contextlib.contextmanager
def interrupt_on_sigterm():
    def interrupted(signum, frame):
        raise KeyboardInterrupt

    previous_handler = signal.signal(signal.SIGTERM, interrupted)
    try:
        yield
    finally:
        signal.signal(signal.SIGTERM, previous_handler)


def owned_group_running(process_id):
    for entry in Path("/proc").iterdir():
        if entry.name.isdecimal():
            try:
                fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
                if int(fields[2]) == process_id and fields[0] not in ("Z", "X"):
                    return True
            except (OSError, ValueError, IndexError):
                pass
    return False


def stop_owned_process(process, stop_signal=signal.SIGTERM):
    previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGTERM, signal.SIGINT})
    try:
        deadline = time.monotonic() + 5
        escalated = stop_signal == signal.SIGKILL
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, stop_signal)
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
            escalated = True
            process.communicate()
        except (OSError, ValueError):
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
            escalated = True
            process.wait()
        while owned_group_running(process.pid):
            if not escalated and time.monotonic() >= deadline:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)
                escalated = True
            time.sleep(0.01)
    finally:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)


def communicate_owned_process(process, timeout=None, timeout_message="Pilot process timed out; no retry was made",
                              stop_signal=signal.SIGTERM):
    completed = False
    try:
        output = process.communicate(timeout=timeout)
        if owned_group_running(process.pid):
            stop_owned_process(process, stop_signal)
        completed = True
        return output
    except subprocess.TimeoutExpired:
        raise PilotError(timeout_message) from None
    finally:
        if not completed:
            stop_owned_process(process, stop_signal)


def run_owned_process(command, timeout=None, check=False, stop_signal=signal.SIGTERM, **arguments):
    with subprocess.Popen(command, start_new_session=True, **arguments) as process:
        output, errors = communicate_owned_process(process, timeout, stop_signal=stop_signal)
        result = subprocess.CompletedProcess(command, process.returncode, output, errors)
    if check and result.returncode:
        raise subprocess.CalledProcessError(result.returncode, command)
    return result


def private_directory(path):
    path.mkdir(parents=True, mode=0o700, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise PilotError("Pilot directories must be owned by you, private (0700), and not symlinks")
    return path


def private_json(path, value):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")
    os.replace(temporary, path)


def require_private_path(path, directory=False, executable=False):
    try:
        info = path.lstat()
    except OSError:
        raise PilotError("Required private pilot path is missing or inaccessible") from None
    expected_mode = 0o700 if directory or executable else 0o600
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if (not expected_type(info.st_mode) or info.st_uid != os.getuid() or
            stat.S_IMODE(info.st_mode) != expected_mode):
        raise PilotError("Pilot paths must be owned by you, private, and not symlinks")


def unique_json_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON field")
        value[key] = item
    return value


def read_private_json(path):
    require_private_path(path)
    try:
        return json.loads(path.read_text(), object_pairs_hook=unique_json_object)
    except (OSError, ValueError):
        raise PilotError("Required pilot JSON is missing or invalid") from None


@contextlib.contextmanager
def pilot_lock(root, existing=False):
    flags = os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW
    if not existing:
        flags |= os.O_CREAT
    try:
        descriptor = os.open(root / "pilot.lock", flags, 0o600)
    except OSError:
        raise PilotError("The private pilot lock is missing or inaccessible") from None
    with os.fdopen(descriptor, "a") as lock:
        info = os.fstat(lock.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or
                stat.S_IMODE(info.st_mode) != 0o600):
            raise PilotError("The pilot lock must be an owned private regular file")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise PilotError("Another pilot command is already running") from None
        yield


def validate_model(model):
    if not re.fullmatch(r"[A-Za-z0-9._:/-]{1,160}", model):
        raise PilotError("Select an exact model ID from probe; no automatic model fallback is permitted")


def proxy_address(value):
    if not value:
        return None
    parsed = urllib.parse.urlsplit(value)
    try:
        port = parsed.port if parsed.port is not None else 80
    except ValueError:
        raise PilotError("Use an HTTP proxy host and port without embedded credentials") from None
    if (parsed.scheme != "http" or not parsed.hostname or parsed.username is not None or parsed.password is not None or
            parsed.path not in ("", "/") or parsed.query or parsed.fragment or not 1 <= port <= 65535):
        raise PilotError("Use an HTTP proxy host and port without embedded credentials")
    return parsed.hostname, port


def validate_proxy_credentials(proxy, username, password):
    if username is None and password is None:
        return
    if not proxy or not isinstance(username, str) or not isinstance(password, str) or not username.strip():
        raise PilotError("Proxy authentication requires an HTTP proxy, username, and password")
    if (len(username.encode("utf-8")) > 255 or ":" in username or any(ord(char) < 32 or ord(char) == 127 for char in username) or
            not password or len(password.encode("utf-8")) > 255 or
            any(ord(char) < 32 or ord(char) == 127 for char in password)):
        raise PilotError("Proxy credentials must be 1..255 bytes without control characters")


def destination(header):
    first_line = header.split(b"\r\n", 1)[0].decode("ascii")
    match = re.fullmatch(r"CONNECT ([a-z0-9.-]+):443 HTTP/1\.[01]", first_line)
    if not match or match.group(1) not in ALLOWED_HOSTS:
        raise PilotError("The pilot only permits HTTPS CONNECT to its Google endpoint allowlist")
    return match.group(1)


def read_header(connection):
    header = bytearray()
    while not header.endswith(b"\r\n\r\n"):
        chunk = connection.recv(1)
        if not chunk or len(header) >= 16_384:
            raise PilotError("Invalid proxy header")
        header.extend(chunk)
    return bytes(header)


def relay(left, right):
    while True:
        ready, _, _ = select.select([left, right], [], [], 180)
        if not ready:
            return
        for source in ready:
            data = source.recv(65_536)
            if not data:
                return
            (right if source is left else left).sendall(data)


class PrivateProxy(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True


class BasicAuthProxyHandler(urllib.request.ProxyHandler):
    def __init__(self, proxies, username, password):
        credentials = ":".join((username, password)).encode("utf-8")
        self.authorization = "Basic " + base64.b64encode(credentials).decode("ascii")
        super().__init__(proxies)

    def proxy_open(self, request, proxy, proxy_type):
        if request.host and urllib.request.proxy_bypass(request.host):
            return super().proxy_open(request, proxy, proxy_type)
        request.add_unredirected_header("Proxy-Authorization", self.authorization)
        return super().proxy_open(request, proxy, proxy_type)


class ConfigurableProxyHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(20)
        try:
            host = destination(read_header(self.request))
            address = (host, 443) if self.server.upstream is None else self.server.upstream
            with socket.create_connection(address, timeout=20) as upstream:
                if self.server.upstream is not None:
                    line_break = bytes((13, 10))
                    headers = [f"CONNECT {host}:443 HTTP/1.1", f"Host: {host}:443"]
                    if self.server.proxy_credentials is not None:
                        raw = ":".join(self.server.proxy_credentials).encode("utf-8")
                        headers.append("Proxy-Authorization: Basic " + base64.b64encode(raw).decode("ascii"))
                    request = b"".join(header.encode("ascii") + line_break for header in headers) + line_break
                    upstream.sendall(request)
                    response = read_header(upstream)
                    status = response.split(line_break, 1)[0]
                    if status.split(b" ", 2)[:2] not in ([b"HTTP/1.0", b"200"], [b"HTTP/1.1", b"200"]):
                        raise PilotError("Upstream proxy rejected the connection")
                observer = getattr(self.server, "on_connect", None)
                if observer is not None:
                    observer(host)
                self.request.sendall(b"HTTP/1.1 200 Connection established" + bytes((13, 10, 13, 10)))
                relay(self.request, upstream)
        except (OSError, UnicodeError, PilotError):
            with contextlib.suppress(OSError):
                self.request.sendall(b"HTTP/1.1 403 Forbidden" + bytes((13, 10)) +
                                     b"Content-Length: 0" + bytes((13, 10, 13, 10)))


@contextlib.contextmanager
def bridge(directory, proxy, username=None, password=None, on_connect=None):
    with PrivateProxy(str(directory / "egress.sock"), ConfigurableProxyHandler) as server:
        server.upstream = proxy_address(proxy)
        server.proxy_credentials = (username, password) if username is not None else None
        server.on_connect = on_connect
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield
        finally:
            server.shutdown()
            thread.join(timeout=2)


def release_opener(proxy, username=None, password=None):
    if not proxy:
        return urllib.request.build_opener(urllib.request.ProxyHandler({}))
    handler = BasicAuthProxyHandler({"https": proxy}, username, password) if username is not None else urllib.request.ProxyHandler({"https": proxy})
    return urllib.request.build_opener(handler)


def sandbox_command(root, runtime, online, arguments, login=False):
    command = ["/usr/bin/bwrap", "--die-with-parent", "--new-session", "--unshare-all",
               "--cap-drop", "ALL", "--clearenv", "--ro-bind", "/usr", "/usr"]
    for name in ("bin", "lib", "lib64", "sbin"):
        path = Path("/") / name
        if path.is_symlink():
            command += ["--symlink", os.readlink(path), str(path)]
        elif path.exists():
            command += ["--ro-bind", str(path), str(path)]
    command += ["--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--dir", "/etc",
                "--ro-bind", "/etc/ssl/certs", "/etc/ssl/certs",
                "--ro-bind", str(root / "bin/agy"), "/opt/agy",
                "--ro-bind", str(Path(__file__).resolve()), "/opt/pilot.py",
                "--bind", str(root / "home"), "/home/pilot",
                "--bind", str(runtime / "workspace"), "/workspace",
                "--chdir", "/workspace", "--setenv", "HOME", "/home/pilot",
                "--setenv", "PATH", "/usr/bin:/bin", "--setenv", "LANG", "C.UTF-8",
                "--setenv", "TERM", "xterm-256color", "--setenv", "XDG_CONFIG_HOME", "/home/pilot/.config",
                "--setenv", "XDG_CACHE_HOME", "/home/pilot/.cache", "--setenv", "XDG_STATE_HOME", "/home/pilot/.local/state"]
    if not login:
        command += ["--bind", str(root / "home/.gemini"), "/home/pilot/.gemini",
                    "--bind", str(root / "home/.gemini/antigravity-cli"), "/home/pilot/.gemini/antigravity-cli",
                    "--ro-bind", str(root / "policy.json"), "/home/pilot/.gemini/antigravity-cli/settings.json"]
    if online:
        command += ["--ro-bind", str(runtime / "egress.sock"), "/run/pilot-egress.sock",
                    "--setenv", "HTTP_PROXY", "http://127.0.0.1:18080",
                    "--setenv", "HTTPS_PROXY", "http://127.0.0.1:18080"]
    command += ["/usr/bin/python3", "/opt/pilot.py", "inside", "online" if online else "offline", "--", *arguments]
    return command


class RelayHandler(socketserver.BaseRequestHandler):
    def handle(self):
        with socket.socket(socket.AF_UNIX) as connection:
            try:
                connection.connect("/run/pilot-egress.sock")
                relay(self.request, connection)
            except OSError:
                return


def inside(arguments):
    online, separator, *command = arguments
    if separator != "--":
        raise PilotError("Invalid sandbox arguments")
    if online == "online":
        server = socketserver.ThreadingTCPServer(("127.0.0.1", 18080), RelayHandler)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
    return run_owned_process(command, stop_signal=signal.SIGKILL).returncode


def check_sandbox(root, proxy, username=None, password=None):
    with tempfile.TemporaryDirectory(prefix="sandbox-", dir=root) as name:
        runtime = Path(name)
        (runtime / "workspace").mkdir(mode=0o700)
        code = (
            "import os,socket,pathlib; "
            "assert not pathlib.Path('/home/soven').exists(); "
            "assert not pathlib.Path('/var/run/docker.sock').exists(); "
            "assert not any(k in os.environ for k in ['GEMINI_API_KEY','GOOGLE_API_KEY','DBUS_SESSION_BUS_ADDRESS']); "
            "assert pathlib.Path('/home/pilot/.gemini/antigravity-cli/settings.json').read_text(); "
            "sock=socket.socket(); assert sock.connect_ex(('127.0.0.1',7897)) != 0; "
            "assert pathlib.Path('/proc/1').exists()\n"
            "try:\n pathlib.Path('/home/pilot/.gemini/antigravity-cli/settings.json').write_text('{}')\n"
            "except OSError:\n pass\nelse:\n raise AssertionError('Policy is not read-only')\n"
        )
        run_owned_process(sandbox_command(root, runtime, False, ["/usr/bin/python3", "-c", code]), check=True,
                       env={}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        with bridge(runtime, proxy, username, password):
            code = (
                "import socket,urllib.request; "
                "proxy=urllib.request.ProxyHandler({'https':'http://127.0.0.1:18080'}); "
                "response=urllib.request.build_opener(proxy).open('https://oauth2.googleapis.com/',timeout=25)"
            )
            result = run_owned_process(sandbox_command(root, runtime, True, ["/usr/bin/python3", "-c",
                "import urllib.error\ntry:\n " + code + "\nexcept urllib.error.HTTPError as error:\n assert error.code in (400,404)\n"]),
                env={}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=35)
            if result.returncode:
                raise PilotError("Isolated HTTPS egress check failed; no direct-network fallback was used")
            code = (
                "import socket; sock=socket.create_connection(('127.0.0.1',18080)); "
                "sock.sendall(b'CONNECT generativelanguage.googleapis.com:443 HTTP/1.1\\r\\n\\r\\n'); "
                "assert sock.recv(1024).startswith(b'HTTP/1.1 403')"
            )
            run_owned_process(sandbox_command(root, runtime, True, ["/usr/bin/python3", "-c", code]),
                           env={}, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)


def prepare_files(root):
    for directory in (root, root / "bin", root / "home", root / "reports", root / "work",
                      root / "home/.gemini", root / "home/.gemini/antigravity-cli"):
        private_directory(directory)
    policy = root / "policy.json"
    if policy.exists() and json.loads(policy.read_text()) != POLICY:
        raise PilotError("Pilot policy changed; refusing to enable credits or alternate authentication")
    private_json(policy, POLICY)
    settings = root / "home/.gemini/antigravity-cli/settings.json"
    if not settings.exists():
        private_json(settings, POLICY)


def pinned_release():
    try:
        return json.loads((REPO / "scripts/antigravity-acp-release.json").read_text(),
                          object_pairs_hook=unique_json_object)
    except (OSError, ValueError):
        raise PilotError("Pinned CLI release is missing or invalid") from None


def verify_installed_release(root, release):
    binary = root / "bin/agy"
    require_private_path(binary, executable=True)
    stored = read_private_json(root / "release.json")
    with binary.open("rb") as content:
        digest = hashlib.file_digest(content, "sha512").hexdigest()
    if not isinstance(stored, dict) or stored.get("release") != release or stored.get("binarySha512") != digest:
        raise PilotError("Installed CLI failed its pinned integrity check")


def existing_private_file(path, executable=False):
    try:
        path.lstat()
    except FileNotFoundError:
        return False
    require_private_path(path, executable=executable)
    return True


def install_release(root, proxy, username=None, password=None):
    release = pinned_release()
    binary = root / "bin/agy"
    receipt = root / "release.json"
    require_private_path(root, directory=True)
    require_private_path(binary.parent, directory=True)
    has_binary = existing_private_file(binary, executable=True)
    has_receipt = existing_private_file(receipt)
    if has_binary and has_receipt:
        verify_installed_release(root, release)
        return
    stored = read_private_json(receipt) if has_receipt else None
    if has_receipt and (not isinstance(stored, dict) or stored.get("release") != release or
                        not isinstance(stored.get("binarySha512"), str) or
                        not re.fullmatch(r"[0-9a-f]{128}", stored["binarySha512"])):
        raise PilotError("Incomplete CLI receipt failed its pinned integrity check")
    parsed = urllib.parse.urlsplit(release["url"])
    if parsed.scheme != "https" or parsed.hostname != "storage.googleapis.com" or not parsed.path.startswith("/antigravity-public/antigravity-cli/"):
        raise PilotError("Release is not an official Antigravity download")
    opener = release_opener(proxy, username, password)
    with tempfile.TemporaryDirectory(prefix="download-", dir=root) as name:
        archive = Path(name) / "release.tar.gz"
        staged_binary = Path(name) / "agy"
        staged_receipt = Path(name) / "release.json"
        with opener.open(release["url"], timeout=60) as source, archive.open("wb") as output:
            shutil.copyfileobj(source, output)
        with archive.open("rb") as source:
            if hashlib.file_digest(source, "sha512").hexdigest() != release["sha512"]:
                raise PilotError("Official archive SHA-512 mismatch")
        with tarfile.open(archive) as source:
            member = source.getmember("antigravity")
            if not member.isfile() or member.size > 1_000_000_000:
                raise PilotError("Unexpected executable in official archive")
            with source.extractfile(member) as content, staged_binary.open("xb") as output:
                os.fchmod(output.fileno(), 0o600)
                shutil.copyfileobj(content, output)
        with staged_binary.open("rb") as source:
            digest = hashlib.file_digest(source, "sha512").hexdigest()
        staged_binary.chmod(0o700)
        if has_binary:
            with binary.open("rb") as source:
                if hashlib.file_digest(source, "sha512").hexdigest() != digest:
                    raise PilotError("Unrecognized orphan CLI binary; refusing to replace it")
        if has_receipt and stored["binarySha512"] != digest:
            raise PilotError("Incomplete CLI receipt failed its pinned integrity check")
        private_json(staged_receipt, {"release": release, "binarySha512": digest})
        if not has_binary:
            os.link(staged_binary, binary)
        if not has_receipt:
            os.link(staged_receipt, receipt)
        verify_installed_release(root, release)


def run_agent(root, proxy, online, arguments, runner=None, username=None, password=None):
    with tempfile.TemporaryDirectory(prefix="agent-", dir=root) as name:
        runtime = Path(name)
        (runtime / "workspace").mkdir(mode=0o700)
        with bridge(runtime, proxy, username, password) if online else contextlib.nullcontext():
            login = "--login" in arguments
            command = ["/opt/agy", arguments[0], "--disable-telemetry", "--include-default-customizations=false",
                       "--cli_log_file", "/dev/null", "--log-file", "/dev/null", *arguments[1:]]
            try:
                return (runner or run_owned_process)(
                    sandbox_command(root, runtime, online, command, login=login),
                    env={}, stop_signal=signal.SIGKILL).returncode
            finally:
                if login:
                    private_json(root / "home/.gemini/antigravity-cli/settings.json", POLICY)


def validate_gateway_state(root, model):
    validate_model(model)
    for directory in (root, root / "bin", root / "home", root / "reports", root / "work",
                      root / "home/.gemini", root / "home/.gemini/antigravity-cli"):
        require_private_path(directory, directory=True)
    for policy in (root / "policy.json", root / "home/.gemini/antigravity-cli/settings.json"):
        if json.dumps(read_private_json(policy), sort_keys=True) != json.dumps(POLICY, sort_keys=True):
            raise PilotError("Pilot policy changed; refusing gateway execution")
    verify_installed_release(root, pinned_release())
    report = read_private_json(root / "reports/verification.json")
    if (not isinstance(report, dict) or report.get("status") != "REAL_ACP_ACCEPTANCE_PASSED" or
            report.get("modelId") != model or
            any(report.get(field) is not True for field in ("realGenerationVerified", "cancelAcknowledged", "credentialsReused")) or
            report.get("extraCredits", False) is not False):
        raise PilotError("Gateway requires successful ACP acceptance for the exact model with no extra credits")


def run_gateway_agent(root, proxy, model, username=None, password=None):
    validate_gateway_state(root, model)

    def interrupted(signum, frame):
        raise PilotError("Gateway ACP execution interrupted")

    previous_handler = signal.signal(signal.SIGTERM, interrupted)
    try:
        return run_agent(root, proxy, True, ["acp"], username=username, password=password)
    finally:
        signal.signal(signal.SIGTERM, previous_handler)


def invalidate_verification(root):
    (root / "reports/verification.json").unlink(missing_ok=True)


def run_checks(root, proxy, mode, model="", online=True):
    destination = root / "reports" / ("verification.json" if mode == "verify" else "probe.json")
    destination.unlink(missing_ok=True)
    jar = REPO / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
    if not jar.exists():
        raise PilotError("Build the pilot with prepare before running checks")
    command = [shutil.which("java"), "-cp", str(jar), MAIN_CLASS, mode, str(root / "work"), model, "--",
               sys.executable, str(Path(__file__).resolve()), "--state", str(root), "--proxy", proxy, "agent"]
    if online:
        command += ["--online"]
    command += ["--", "acp"]
    result = run_owned_process(command, cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        report = json.loads(result.stdout)
        if not isinstance(report, dict) or not isinstance(report.get("status"), str):
            raise ValueError("Invalid report")
    except ValueError:
        raise PilotError("Acceptance process failed before producing a sanitized report") from None
    if mode == "verify" and result.returncode and report["status"] == "REAL_ACP_ACCEPTANCE_PASSED":
        raise PilotError("Acceptance process failed despite reporting a pass")
    try:
        private_json(destination, report)
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    except BaseException:
        destination.unlink(missing_ok=True)
        raise
    return result.returncode


@interrupt_on_sigterm()
def main():
    if len(sys.argv) > 1 and sys.argv[1] == "inside":
        return inside(sys.argv[2:])
    parser = argparse.ArgumentParser(description="Isolated official Antigravity subscription acceptance pilot")
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--proxy", default="")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("prepare")
    sub.add_parser("login")
    sub.add_parser("probe")
    verify = sub.add_parser("verify")
    verify.add_argument("--model", required=True)
    gateway = sub.add_parser("gateway-agent", help="Run ACP only after pinned CLI and exact-model acceptance checks")
    gateway.add_argument("--model", required=True)
    agent = sub.add_parser("agent", help=argparse.SUPPRESS)
    agent.add_argument("--online", action="store_true")
    agent.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    os.umask(0o077)
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise PilotError("This pinned pilot only supports Linux x86_64")
    root = args.state.absolute()
    proxy_address(args.proxy)
    username = os.environ.get("AGY_PROXY_USERNAME") or None
    password = os.environ.get("AGY_PROXY_PASSWORD") or None
    validate_proxy_credentials(args.proxy, username, password)
    if args.action == "agent":
        command = args.arguments[1:] if args.arguments[:1] == ["--"] else args.arguments
        if command != ["acp"]:
            raise PilotError("Only ACP is permitted in the agent subprocess")
        return run_agent(root, args.proxy, args.online, command, username=username, password=password)
    if args.action in ("verify", "gateway-agent"):
        validate_model(args.model)
    if args.action == "gateway-agent":
        if not args.state.is_absolute():
            raise PilotError("Gateway state must be an absolute path")
        require_private_path(root, directory=True)
    else:
        private_directory(root)
    with pilot_lock(root, existing=args.action == "gateway-agent"):
        if args.action == "gateway-agent":
            return run_gateway_agent(root, args.proxy, args.model, username, password)
        prepare_files(root)
        if args.action in ("login", "verify"):
            invalidate_verification(root)
        if args.action in ("prepare", "probe"):
            (root / "reports/probe.json").unlink(missing_ok=True)
        if args.action == "prepare":
            (root / "reports/ready.json").unlink(missing_ok=True)
            print("Installing and verifying the pinned official CLI...", flush=True)
            install_release(root, args.proxy, username, password)
            print("Checking filesystem/process/network isolation and Google-only HTTPS egress...", flush=True)
            check_sandbox(root, args.proxy, username, password)
            print("Building the standalone acceptance entry (no gateway is started)...", flush=True)
            run_owned_process([shutil.which("mvn"), "--batch-mode", "--no-transfer-progress", "-pl", "prism-app", "-am",
                            "-DskipTests", "package"], cwd=REPO, check=True)
            result = run_checks(root, args.proxy, "probe", online=False)
            if result not in (0, 10):
                return result
            private_json(root / "reports/ready.json", {"status": "READY_FOR_GOOGLE_LOGIN", "cliVersion": "1.5.17",
                         "sandbox": "private-filesystem-pid-network", "extraCredits": False, "realGenerationVerified": False})
            print("READY_FOR_GOOGLE_LOGIN: run the login command in your own terminal. Do not paste codes into chat.")
            return 0
        install_release(root, args.proxy, username, password)
        if args.action == "login":
            print("Choose Log in with Google for your own subscription account. Enter the code only in this terminal.", flush=True)
            return run_agent(root, args.proxy, True, ["acp", "--login"], username=username, password=password)
        if args.action == "probe":
            return run_checks(root, args.proxy, "probe")
        return run_checks(root, args.proxy, "verify", args.model)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PilotError as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, tarfile.TarError, KeyboardInterrupt):
        print("Pilot stopped: preparation or policy check failed. No billing or direct-network fallback was used.", file=sys.stderr)
        sys.exit(1)

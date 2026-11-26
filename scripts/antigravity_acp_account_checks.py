#!/usr/bin/env python3
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import threading

import antigravity_acp_pilot as pilot


def summarize_status(output, exit_code, connections):
    if len(output) > 524_288:
        raise pilot.PilotError("Official status output exceeded its limit")
    try:
        value = json.loads(output, object_pairs_hook=pilot.unique_json_object)
    except (ValueError, UnicodeError):
        raise pilot.PilotError("Official CLI did not return a structured status result") from None
    if not isinstance(value, dict):
        raise pilot.PilotError("Invalid official status result")
    text = value.get("response", "")
    error = value.get("error", "")
    if not isinstance(text, str) or not isinstance(error, (str, type(None))):
        raise pilot.PilotError("Invalid official status text")
    combined = (text + "\n" + (error or "")).lower()
    exhausted = any(marker in combined for marker in (
        "resource_exhausted", "quota exhausted", "quota exceeded", "rate limit exceeded", "insufficient quota",
    )) or re.search(r"(?<![a-z0-9])(?:limit +remaining|quota +remaining|remaining(?: +quota)?) *[:：]? *0(?:[.]0+)? *%", combined) is not None
    disabled = "this service has been disabled in this account" in combined
    usage = value.get("usage")
    turns = value.get("num_turns")
    zero_usage = isinstance(usage, dict) and bool(usage) and all(
        type(amount) is int and amount == 0 for amount in usage.values()
    )
    readonly = exit_code == 0 and not error and type(turns) is int and turns == 0 and zero_usage
    matches = list(re.finditer(
        r"(?im)^\s*(?:current\s+plan|plan|subscription(?:\s+tier)?|tier)\s*[:：]\s*"
        r"(?:google\s+(?:ai|one)\s+)?(pro|ultra|plus|ai premium|free)\s*$", text,
    )) if readonly else []
    match = matches[0] if len(matches) == 1 else None
    tier = match.group(1).upper().replace(" ", "_") if match else None
    status = ("QUOTA_EXHAUSTED" if exhausted else "SERVICE_DISABLED" if disabled else
              "OFFICIAL_READ_ONLY_STATUS_VERIFIED" if readonly else "OFFICIAL_STATUS_NOT_VERIFIED")
    return {
        "status": status,
        "verifiedAt": datetime.now(timezone.utc).isoformat(),
        "officialCommand": "agy --output-format json --print /usage",
        "readOnlyStatusVerified": readonly,
        "generationTurns": turns if type(turns) is int and 0 <= turns <= 100 else None,
        "zeroReportedGenerationUsage": zero_usage,
        "subscriptionTier": tier,
        "subscriptionEntitlementVerified": readonly and tier in {"PRO", "ULTRA", "PLUS", "AI_PREMIUM"},
        "subscriptionEvidence": "OFFICIAL_USAGE_PLAN_LINE" if tier else "PLAN_NOT_EXPOSED_BY_OFFICIAL_STATUS",
        "oauthTokenEndpointConnections": connections.get("oauth2.googleapis.com", 0),
        "oauthTokenEndpointObserved": connections.get("oauth2.googleapis.com", 0) > 0,
        "refreshAfterExpiryVerified": False,
        "refreshEvidence": "NO_OFFICIAL_PRE_REFRESH_EXPIRY_METADATA",
        "credentialFilesRead": False,
        "tokenEndpointBodiesInspected": False,
        "tokenOrClockModified": False,
        "extraCredits": False,
        "modelSwitches": 0,
        "fallbacks": 0,
        "canContinueAcceptance": readonly and not exhausted and not disabled,
    }


def check_account(root, proxy, model):
    pilot.validate_model(model)
    with pilot.pilot_lock(root, existing=True):
        pilot.require_private_path(root, directory=True)
        pilot.require_private_path(root / "reports", directory=True)
        destination = root / "reports/account-verification.json"
        pending = {"status": "ACCOUNT_EVIDENCE_CHECK_RUNNING", "canContinueAcceptance": False, "modelId": model}
        pilot.private_json(destination, pending)
        try:
            pilot.validate_gateway_state(root, model)
            report = observe_official_status(root, proxy)
            if report["canContinueAcceptance"]:
                report = merge_session_evidence(report, observe_official_session(root, proxy, model), model)
        except (pilot.PilotError, OSError, ValueError, KeyboardInterrupt):
            pilot.private_json(destination, {**pending, "status": "ACCOUNT_EVIDENCE_CHECK_STOPPED"})
            raise
        report["modelId"] = model
        pilot.private_json(destination, report)
        return report


def merge_session_evidence(report, session, model):
    result = dict(report)
    tier = session.get("subscriptionTier")
    known_tiers = {"PRO", "ULTRA", "PLUS", "AI_PREMIUM", "FREE"}
    verified = (
        session.get("status") == "OFFICIAL_ACP_ACCOUNT_STATUS_VERIFIED" and
        session.get("modelId") == model and session.get("currentModelMatches") is True and
        type(session.get("generationRequests")) is int and session["generationRequests"] == 0 and
        type(session.get("modelSwitches")) is int and session["modelSwitches"] == 0 and
        type(session.get("fallbacks")) is int and session["fallbacks"] == 0 and
        session.get("realGenerationVerified") is False and
        (tier is None or isinstance(tier, str) and tier in known_tiers) and
        session.get("subscriptionEvidence") == (
            "PLAN_NOT_EXPOSED_BY_OFFICIAL_ACP_SESSION" if tier is None else "OFFICIAL_ACP_SESSION_TIER_METADATA")
    )
    result["officialAcpSessionVerified"] = verified
    result["canContinueAcceptance"] = report.get("canContinueAcceptance") is True and verified
    result["refreshAfterExpiryVerified"] = False
    result["refreshEvidence"] = "NO_OFFICIAL_PRE_REFRESH_EXPIRY_METADATA"
    result["naturalExpiryObservationStarted"] = False
    result["naturalExpiryObservationBlockedBy"] = "OFFICIAL_EXPIRY_METADATA_UNAVAILABLE"
    connections = session.get("oauthTokenEndpointConnections", 0)
    if type(connections) is int and connections >= 0:
        result["oauthTokenEndpointConnections"] += connections
    result["oauthTokenEndpointObserved"] = result["oauthTokenEndpointConnections"] > 0
    if not verified:
        status = session.get("status")
        result["status"] = (status if isinstance(status, str) and status in {"QUOTA_EXHAUSTED", "SERVICE_DISABLED", "MODEL_MISMATCH", "MODEL_UNAVAILABLE"}
                            else "OFFICIAL_ACP_STATUS_NOT_VERIFIED")
        result["subscriptionTier"] = None
        result["subscriptionEntitlementVerified"] = False
        result["subscriptionEvidence"] = "OFFICIAL_ACP_SESSION_NOT_VERIFIED"
    elif report.get("subscriptionTier") is not None and tier is not None and report["subscriptionTier"] != tier:
        result["status"] = "OFFICIAL_SUBSCRIPTION_EVIDENCE_CONFLICT"
        result["canContinueAcceptance"] = False
        result["subscriptionTier"] = None
        result["subscriptionEntitlementVerified"] = False
        result["subscriptionEvidence"] = "OFFICIAL_STATUS_AND_SESSION_DISAGREE"
    elif tier is not None:
        result["subscriptionTier"] = tier
        result["subscriptionEntitlementVerified"] = tier != "FREE" and result["canContinueAcceptance"]
        result["subscriptionEvidence"] = "OFFICIAL_ACP_SESSION_TIER_METADATA"
    return result


def stop_owned_process(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate()


def communicate_owned_process(process, timeout, timeout_message):
    try:
        return process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        stop_owned_process(process)
        raise pilot.PilotError(timeout_message) from None
    except BaseException:
        stop_owned_process(process)
        raise


def observe_official_session(root, proxy, model):
    java = shutil.which("java")
    jar = pilot.REPO / "prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar"
    if not java or not jar.is_file():
        raise pilot.PilotError("Build the isolated account-status runner before checking accounts")
    connections = Counter()
    lock = threading.Lock()

    def observed(host):
        with lock:
            connections[host] += 1

    with tempfile.TemporaryDirectory(prefix="account-session-", dir=root) as name:
        runtime = Path(name)
        (runtime / "workspace").mkdir(mode=0o700)
        with pilot.bridge(runtime, proxy, on_connect=observed):
            agent = ["/opt/agy", "acp", "--disable-telemetry", "--include-default-customizations=false",
                     "--cli_log_file", "/dev/null", "--log-file", "/dev/null"]
            command = [java, "-cp", str(jar), pilot.MAIN_CLASS, "account-status", str(runtime), model, "--",
                       *pilot.sandbox_command(root, runtime, True, agent)]
            with subprocess.Popen(command, cwd=pilot.REPO, env={}, start_new_session=True, stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
                output, _ = communicate_owned_process(
                    process, 80, "Official ACP account status timed out; no retry was made")
        if len(output) > 65_536:
            raise pilot.PilotError("Official ACP account status exceeded its limit")
        try:
            report = json.loads(output, object_pairs_hook=pilot.unique_json_object)
            if not isinstance(report, dict) or not isinstance(report.get("status"), str):
                raise ValueError("Invalid official session report")
        except (ValueError, UnicodeError):
            raise pilot.PilotError("Official ACP account status did not return a sanitized result") from None
        if process.returncode and report["status"] == "OFFICIAL_ACP_ACCOUNT_STATUS_VERIFIED":
            raise pilot.PilotError("Official session process failed after reporting success")
        report["oauthTokenEndpointConnections"] = connections["oauth2.googleapis.com"]
        return report


def observe_official_status(root, proxy):
    connections = Counter()
    lock = threading.Lock()

    def observed(host):
        with lock:
            connections[host] += 1

    with tempfile.TemporaryDirectory(prefix="account-check-", dir=root) as name:
        runtime = Path(name)
        (runtime / "workspace").mkdir(mode=0o700)
        with pilot.bridge(runtime, proxy, on_connect=observed):
            command = ["/opt/agy", "--log-file", "/dev/null", "--output-format", "json", "--print", "/usage"]
            with subprocess.Popen(pilot.sandbox_command(root, runtime, True, command), env={}, start_new_session=True,
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
                output, _ = communicate_owned_process(
                    process, 45, "Official read-only status timed out; no retry was made")
        return summarize_status(output, process.returncode, connections)


def main():
    parser = argparse.ArgumentParser(description="Official read-only account evidence, without reading CLI credentials")
    parser.add_argument("--state", type=Path, default=pilot.DEFAULT_STATE)
    parser.add_argument("--proxy", default="http://127.0.0.1:7897")
    parser.add_argument("--model", required=True)
    args = parser.parse_args()
    os.umask(0o077)

    def interrupted(signum, frame):
        raise KeyboardInterrupt

    previous_handler = signal.signal(signal.SIGTERM, interrupted)
    try:
        if not args.state.is_absolute():
            raise pilot.PilotError("State must be an absolute private directory")
        report = check_account(args.state, args.proxy, args.model)
        print(json.dumps(report))
        return 0 if report["canContinueAcceptance"] else 1
    except (pilot.PilotError, OSError, ValueError, KeyboardInterrupt):
        print(json.dumps({"status": "ACCOUNT_EVIDENCE_CHECK_STOPPED", "canContinueAcceptance": False}))
        return 1
    finally:
        signal.signal(signal.SIGTERM, previous_handler)


if __name__ == "__main__":
    raise SystemExit(main())

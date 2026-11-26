#!/usr/bin/env python3
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import time

import antigravity_acp_account_checks as checks
import antigravity_acp_pilot as pilot


class RefreshStopped(pilot.PilotError):
    pass


def utc_now():
    return datetime.now(timezone.utc)


def read_isolated_expiry(root):
    descriptors = []
    try:
        descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
        descriptors.append(descriptor)
        for component in (None, 'home', '.gemini', 'antigravity-cli'):
            if component is not None:
                descriptor = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                                     dir_fd=descriptor)
                descriptors.append(descriptor)
            info = os.fstat(descriptor)
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise RefreshStopped('PRIVATE_CREDENTIAL_DIRECTORY_REQUIRED')
        credential = os.open('antigravity-oauth-token', os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK,
                             dir_fd=descriptor)
        descriptors.append(credential)
        info = os.fstat(credential)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or
                stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1 or info.st_size > 262_144):
            raise RefreshStopped('PRIVATE_CREDENTIAL_FILE_REQUIRED')
        content = os.read(credential, 262_145)
        if len(content) > 262_144:
            raise RefreshStopped('EXPIRY_METADATA_UNAVAILABLE')
        value = json.loads(content, object_pairs_hook=pilot.unique_json_object)
        token_metadata = value.get('token') if isinstance(value, dict) else None
        expiry = token_metadata.get('expiry') if isinstance(token_metadata, dict) else None
        if not isinstance(expiry, str) or len(expiry) > 80:
            raise RefreshStopped('EXPIRY_METADATA_UNAVAILABLE')
        parsed = datetime.fromisoformat(expiry.replace('Z', '+00:00'))
        if parsed.tzinfo is None or not 2020 <= parsed.year <= 2100:
            raise RefreshStopped('EXPIRY_METADATA_UNAVAILABLE')
        return parsed.astimezone(timezone.utc)
    except RefreshStopped:
        raise
    except (OSError, ValueError, UnicodeError, RecursionError):
        raise RefreshStopped('EXPIRY_METADATA_UNAVAILABLE') from None
    finally:
        for descriptor in reversed(descriptors):
            os.close(descriptor)


def wait_for_natural_expiry(expiry, max_wait_seconds):
    started = utc_now()
    monotonic_start = time.monotonic()
    if expiry <= started:
        raise RefreshStopped('EXPIRY_ALREADY_ELAPSED_BEFORE_OBSERVATION')
    if (expiry - started).total_seconds() + 2 > max_wait_seconds:
        raise RefreshStopped('NATURAL_EXPIRY_EXCEEDS_WAIT_BUDGET')
    while True:
        now = utc_now()
        elapsed = time.monotonic() - monotonic_start
        if abs((now - started).total_seconds() - elapsed) > 5:
            raise RefreshStopped('CLOCK_DISCONTINUITY_OBSERVED')
        if elapsed > max_wait_seconds + 5:
            raise RefreshStopped('NATURAL_EXPIRY_WAIT_BUDGET_EXCEEDED')
        remaining = (expiry + timedelta(seconds=2) - now).total_seconds()
        if remaining <= 0:
            return round(elapsed, 3)
        time.sleep(min(remaining, 30))


def protected_hashes(root):
    paths = {'guard': 'reports/verification.json', 'creditPolicy': 'policy.json',
             'settings': 'home/.gemini/antigravity-cli/settings.json',
             'accountEvidence': 'reports/account-verification.json'}
    hashes = {}
    for key, relative in paths.items():
        path = root / relative
        pilot.require_private_path(path)
        hashes[key] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes


def refresh_proven(before_expiry, check_started, after_expiry, checked_at, account):
    return (
        before_expiry <= check_started <= checked_at < after_expiry and after_expiry > before_expiry and
        account.get('canContinueAcceptance') is True and account.get('readOnlyStatusVerified') is True and
        account.get('zeroReportedGenerationUsage') is True and
        type(account.get('generationTurns')) is int and account['generationTurns'] == 0 and
        account.get('officialAcpSessionVerified') is True and
        account.get('subscriptionTier') == 'PRO' and account.get('subscriptionEntitlementVerified') is True and
        account.get('subscriptionEvidence') == 'OFFICIAL_ACP_SESSION_TIER_METADATA' and
        type(account.get('oauthTokenEndpointConnections')) is int and account['oauthTokenEndpointConnections'] > 0 and
        type(account.get('modelSwitches')) is int and account['modelSwitches'] == 0 and
        type(account.get('fallbacks')) is int and account['fallbacks'] == 0 and
        account.get('extraCredits') is False
    )


def verify_natural_refresh(root, proxy, model, allow_read_expiry=False, max_wait_seconds=4500):
    pilot.validate_model(model)
    if type(max_wait_seconds) is not int or not 1 <= max_wait_seconds <= 7200:
        raise RefreshStopped('INVALID_NATURAL_EXPIRY_WAIT_BUDGET')
    with pilot.pilot_lock(root, existing=True):
        pilot.require_private_path(root, directory=True)
        pilot.require_private_path(root / 'reports', directory=True)
        destination = root / 'reports/natural-expiry-verification.json'
        report = {
            'status': 'NATURAL_EXPIRY_CHECK_STARTED', 'startedAt': utc_now().isoformat(), 'modelId': model,
            'refreshAfterExpiryVerified': False, 'refreshEvidence': 'NATURAL_EXPIRY_NOT_YET_VERIFIED',
            'naturalExpiryObservationStarted': False, 'credentialFilesRead': False,
            'credentialReadScope': 'EXPIRY_ONLY', 'expiryMetadataField': 'token.expiry', 'tokensDisplayedOrExported': False,
            'credentialWrittenByVerifier': False, 'tokenEndpointBodiesInspected': False, 'tokenOrClockModifiedByVerifier': False,
            'generationBudget': 0, 'generationTurns': 0, 'extraCredits': False, 'modelSwitches': 0, 'fallbacks': 0,
            'databaseAccessed': False, 'productionRoutesModified': False, 'canContinueAcceptance': False,
        }
        pilot.private_json(destination, report)
        original_hashes = None
        try:
            if allow_read_expiry is not True:
                raise RefreshStopped('EXPLICIT_EXPIRY_READ_CONSENT_REQUIRED')
            pilot.validate_gateway_state(root, model)
            account = pilot.read_private_json(root / 'reports/account-verification.json')
            if (account.get('modelId') != model or account.get('subscriptionTier') != 'PRO' or
                    account.get('subscriptionEntitlementVerified') is not True or
                    account.get('officialAcpSessionVerified') is not True or account.get('canContinueAcceptance') is not True):
                raise RefreshStopped('CURRENT_ISOLATED_PRO_ACCOUNT_REQUIRED')
            original_hashes = protected_hashes(root)
            before_expiry = read_isolated_expiry(root)
            report.update(credentialFilesRead=True, preRefreshExpiry=before_expiry.isoformat(),
                          preExpiryObservedAt=utc_now().isoformat())
            if before_expiry <= utc_now():
                raise RefreshStopped('EXPIRY_ALREADY_ELAPSED_BEFORE_OBSERVATION')
            report.update(status='WAITING_FOR_NATURAL_EXPIRY', naturalExpiryObservationStarted=True)
            pilot.private_json(destination, report)
            print(json.dumps({'status': report['status'], 'preRefreshExpiry': report['preRefreshExpiry'],
                              'generationBudget': 0}), flush=True)
            report['naturalWaitSeconds'] = wait_for_natural_expiry(before_expiry, max_wait_seconds)
            if read_isolated_expiry(root) != before_expiry:
                raise RefreshStopped('CREDENTIAL_EXPIRY_CHANGED_WHILE_WAITING')
            pilot.validate_gateway_state(root, model)
            if protected_hashes(root) != original_hashes:
                raise RefreshStopped('ISOLATION_GUARD_CHANGED')
            check_started = utc_now()
            check_monotonic = time.monotonic()
            report.update(status='CHECKING_OFFICIAL_CLI_AFTER_EXPIRY', checkStartedAt=check_started.isoformat())
            pilot.private_json(destination, report)
            account = checks.observe_official_status(root, proxy)
            if account.get('canContinueAcceptance') is True:
                pilot.validate_gateway_state(root, model)
                if protected_hashes(root) != original_hashes:
                    raise RefreshStopped('ISOLATION_GUARD_CHANGED')
                account = checks.merge_session_evidence(account, checks.observe_official_session(root, proxy, model), model)
            for key in ('subscriptionTier', 'subscriptionEntitlementVerified', 'subscriptionEvidence', 'officialAcpSessionVerified',
                        'readOnlyStatusVerified', 'zeroReportedGenerationUsage', 'generationTurns', 'oauthTokenEndpointConnections'):
                report[key] = account.get(key)
            after_expiry = read_isolated_expiry(root)
            checked_at = utc_now()
            if abs((checked_at - check_started).total_seconds() - (time.monotonic() - check_monotonic)) > 5:
                raise RefreshStopped('CLOCK_DISCONTINUITY_OBSERVED')
            report.update(postRefreshExpiry=after_expiry.isoformat(), checkedAt=checked_at.isoformat())
            proven = refresh_proven(before_expiry, check_started, after_expiry, checked_at, account)
            report.update(refreshAfterExpiryVerified=proven, canContinueAcceptance=proven,
                          status='NATURAL_EXPIRY_REFRESH_VERIFIED' if proven else 'NATURAL_EXPIRY_REFRESH_NOT_VERIFIED',
                          refreshEvidence='OFFICIAL_CLI_EXPIRY_ADVANCED_AFTER_NATURAL_EXPIRY_AND_AUTHENTICATED_ACP'
                          if proven else 'NATURAL_EXPIRY_REFRESH_EVIDENCE_INCOMPLETE')
            if account.get('canContinueAcceptance') is not True:
                allowed = {'QUOTA_EXHAUSTED', 'SERVICE_DISABLED', 'MODEL_MISMATCH', 'MODEL_UNAVAILABLE',
                           'OFFICIAL_SUBSCRIPTION_EVIDENCE_CONFLICT', 'OFFICIAL_ACP_STATUS_NOT_VERIFIED'}
                report['status'] = account.get('status') if account.get('status') in allowed else 'OFFICIAL_ACCOUNT_CHECK_STOPPED'
        except RefreshStopped as error:
            report.update(status=str(error), refreshAfterExpiryVerified=False, canContinueAcceptance=False)
        except (pilot.PilotError, OSError, ValueError, KeyboardInterrupt):
            report.update(status='NATURAL_EXPIRY_CHECK_STOPPED', refreshAfterExpiryVerified=False, canContinueAcceptance=False)
        finally:
            if original_hashes is not None:
                try:
                    current_hashes = protected_hashes(root)
                    report['preservedFiles'] = {key: current_hashes[key] == value for key, value in original_hashes.items()}
                    if not all(report['preservedFiles'].values()):
                        raise pilot.PilotError('Protected file changed')
                except (pilot.PilotError, OSError):
                    report.update(status='ISOLATION_GUARD_CHANGED', refreshAfterExpiryVerified=False, canContinueAcceptance=False)
            report['finishedAt'] = utc_now().isoformat()
            pilot.private_json(destination, report)
        return report


def main():
    parser = argparse.ArgumentParser(description='Verify natural expiry using only consented isolated CLI expiry metadata')
    parser.add_argument('--state', type=Path, default=pilot.DEFAULT_STATE)
    parser.add_argument('--proxy', default='http://127.0.0.1:7897')
    parser.add_argument('--model', required=True)
    parser.add_argument('--allow-read-expiry', action='store_true')
    parser.add_argument('--max-wait-seconds', type=int, default=4500)
    args = parser.parse_args()
    os.umask(0o077)

    def interrupted(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    try:
        if not args.state.is_absolute() or args.state.resolve() != args.state:
            raise RefreshStopped('PRIVATE_ABSOLUTE_STATE_REQUIRED')
        report = verify_natural_refresh(args.state, args.proxy, args.model, args.allow_read_expiry, args.max_wait_seconds)
        print(json.dumps(report), flush=True)
        return 0 if report['refreshAfterExpiryVerified'] else 1
    except (pilot.PilotError, OSError, ValueError, KeyboardInterrupt):
        print(json.dumps({'status': 'NATURAL_EXPIRY_CHECK_STOPPED', 'refreshAfterExpiryVerified': False}), flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

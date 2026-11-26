from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import antigravity_acp_account_checks as checks
import antigravity_acp_natural_refresh as refresh
import antigravity_acp_pilot as pilot


class FakeClock:
    def __init__(self):
        self.wall = datetime(2026, 11, 26, 6, 0, tzinfo=timezone.utc)
        self.elapsed = 0.0

    def sleep(self, seconds):
        self.wall += timedelta(seconds=seconds)
        self.elapsed += seconds


class NaturalRefreshTest(unittest.TestCase):
    def credential(self, root, expiry):
        path = root / 'home/.gemini/antigravity-cli/antigravity-oauth-token'
        path.write_text(json.dumps({'token': {'expiry': expiry, 'access_token': 'private-access-sentinel',
                                             'refresh_token': 'private-refresh-sentinel'}, 'email': 'private@example.invalid'}))
        path.chmod(0o600)
        return path

    @contextmanager
    def environment(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            for relative in ('reports', 'home', 'home/.gemini', 'home/.gemini/antigravity-cli'):
                (root / relative).mkdir(mode=0o700)
            (root / 'pilot.lock').touch(mode=0o600)
            for relative, value in (
                    ('reports/verification.json', {}), ('policy.json', {}),
                    ('home/.gemini/antigravity-cli/settings.json', {}),
                    ('reports/account-verification.json', {'modelId': 'gemini-fixed', 'subscriptionTier': 'PRO',
                     'subscriptionEntitlementVerified': True, 'officialAcpSessionVerified': True, 'canContinueAcceptance': True})):
                pilot.private_json(root / relative, value)
            clock = FakeClock()
            self.credential(root, (clock.wall + timedelta(seconds=5)).isoformat())
            with patch.object(pilot, 'validate_gateway_state'), patch.object(refresh, 'utc_now', side_effect=lambda: clock.wall), \
                    patch.object(refresh.time, 'monotonic', side_effect=lambda: clock.elapsed), \
                    patch.object(refresh.time, 'sleep', side_effect=clock.sleep):
                yield root, clock

    def usage(self, connections=1, response='Remaining quota: 99%', error=''):
        raw = json.dumps({'response': response, 'error': error, 'num_turns': 0,
                          'usage': {'input_tokens': 0, 'output_tokens': 0}}).encode()
        return checks.summarize_status(raw, 1 if error else 0, {'oauth2.googleapis.com': connections})

    def session(self, **updates):
        return {'status': 'OFFICIAL_ACP_ACCOUNT_STATUS_VERIFIED', 'modelId': 'gemini-fixed', 'currentModelMatches': True,
                'generationRequests': 0, 'modelSwitches': 0, 'fallbacks': 0, 'realGenerationVerified': False,
                'subscriptionTier': 'PRO', 'subscriptionEvidence': 'OFFICIAL_ACP_SESSION_TIER_METADATA', **updates}

    def test_only_expiry_is_returned_without_writing_credentials(self):
        with self.environment() as (root, clock):
            path = root / 'home/.gemini/antigravity-cli/antigravity-oauth-token'
            original = path.read_bytes()
            with patch.object(refresh.os, 'open', wraps=os.open) as opened:
                result = refresh.read_isolated_expiry(root)
            self.assertEqual(clock.wall + timedelta(seconds=5), result)
            self.assertEqual(original, path.read_bytes())
            credential_call = next(call for call in opened.call_args_list if call.args[0] == 'antigravity-oauth-token')
            self.assertEqual(os.O_RDONLY, credential_call.args[1] & os.O_ACCMODE)
            self.assertTrue(credential_call.args[1] & os.O_NOFOLLOW)

    def test_unsupported_expiry_fields_are_not_guessed_from_tokens_or_nested_values(self):
        for value in ({'expires_at': '2026-11-26T07:00:00Z'}, {'metadata': {'expiry': '2026-11-26T07:00:00Z'}},
                      {'expiry': '2026-11-26T07:00:00Z'}, {'token': {'expiry': None}}, {'token': {'expiry': 1795676400}},
                      {'token': {'expiry': '2026-11-26T07:00:00'}}, {'token': {'expiry': 'private-secret'}},
                      {'token': {'expiry': '2101-01-01T00:00:00Z'}}, [], True):
            with self.subTest(value=value), self.environment() as (root, clock):
                path = root / 'home/.gemini/antigravity-cli/antigravity-oauth-token'
                path.write_text(json.dumps(value))
                with self.assertRaisesRegex(refresh.RefreshStopped, '^EXPIRY_METADATA_UNAVAILABLE$') as error:
                    refresh.read_isolated_expiry(root)
                self.assertNotIn('private-secret', str(error.exception))

    def test_duplicate_oversized_and_invalid_json_fail_without_exposing_contents(self):
        for content in ('{"token":{"expiry":"2026-11-26T07:00:00Z","expiry":"2026-11-26T08:00:00Z"}}',
                        '{"token":{"expiry":"2026-11-26T07:00:00Z","access_token":"private-secret","access_token":"private-secret"}}',
                        'private-secret', 'x' * 262145):
            with self.subTest(length=len(content)), self.environment() as (root, clock):
                path = root / 'home/.gemini/antigravity-cli/antigravity-oauth-token'
                path.write_text(content)
                with self.assertRaises(refresh.RefreshStopped) as error:
                    refresh.read_isolated_expiry(root)
                self.assertNotIn('private-secret', str(error.exception))

    def test_symlink_and_hardlink_credentials_are_rejected(self):
        for link in ('symlink', 'hardlink'):
            with self.subTest(link=link), self.environment() as (root, clock):
                path = root / 'home/.gemini/antigravity-cli/antigravity-oauth-token'
                original = path.with_name('original')
                path.rename(original)
                path.symlink_to(original) if link == 'symlink' else os.link(original, path)
                with self.assertRaises(refresh.RefreshStopped):
                    refresh.read_isolated_expiry(root)

    def test_permissive_credentials_or_parent_directories_are_rejected(self):
        for relative, mode in (('home', 0o755), ('home/.gemini', 0o755),
                               ('home/.gemini/antigravity-cli/antigravity-oauth-token', 0o644)):
            with self.subTest(relative=relative), self.environment() as (root, clock):
                (root / relative).chmod(mode)
                with self.assertRaises(refresh.RefreshStopped):
                    refresh.read_isolated_expiry(root)

    def test_symlink_parent_directory_is_rejected(self):
        with self.environment() as (root, clock):
            directory = root / 'home/.gemini/antigravity-cli'
            original = directory.with_name('original')
            directory.rename(original)
            directory.symlink_to(original)
            with self.assertRaises(refresh.RefreshStopped):
                refresh.read_isolated_expiry(root)

    def test_named_pipe_is_rejected_without_waiting_for_a_writer(self):
        with self.environment() as (root, clock):
            path = root / 'home/.gemini/antigravity-cli/antigravity-oauth-token'
            path.unlink()
            os.mkfifo(path, 0o600)
            with self.assertRaisesRegex(refresh.RefreshStopped, '^PRIVATE_CREDENTIAL_FILE_REQUIRED$'):
                refresh.read_isolated_expiry(root)

    def test_explicit_consent_is_required_and_previous_success_is_invalidated(self):
        with self.environment() as (root, clock), patch.object(refresh, 'read_isolated_expiry') as read, \
                patch.object(checks, 'observe_official_status') as status:
            pilot.private_json(root / 'reports/natural-expiry-verification.json', {'refreshAfterExpiryVerified': True})
            report = refresh.verify_natural_refresh(root, '', 'gemini-fixed')
            self.assertEqual('EXPLICIT_EXPIRY_READ_CONSENT_REQUIRED', report['status'])
            self.assertFalse(report['refreshAfterExpiryVerified'])
            read.assert_not_called()
            status.assert_not_called()

    def test_observer_waits_naturally_before_one_readonly_check_and_pro_session(self):
        with self.environment() as (root, clock):
            original_time = clock.wall
            def refreshed_status(*args):
                self.assertGreaterEqual(clock.wall, original_time + timedelta(seconds=7))
                self.credential(root, (clock.wall + timedelta(hours=1)).isoformat())
                return self.usage()
            with patch.object(checks, 'observe_official_status', side_effect=refreshed_status) as status, \
                    patch.object(checks, 'observe_official_session', return_value=self.session()) as session:
                report = refresh.verify_natural_refresh(root, 'http://127.0.0.1:7897', 'gemini-fixed', True)
            self.assertEqual('NATURAL_EXPIRY_REFRESH_VERIFIED', report['status'])
            self.assertTrue(report['refreshAfterExpiryVerified'])
            self.assertEqual(7, report['naturalWaitSeconds'])
            self.assertTrue(all(report['preservedFiles'].values()))
            self.assertEqual(0o600, (root / 'reports/natural-expiry-verification.json').stat().st_mode & 0o777)
            self.assertNotIn('private', json.dumps(report))
            status.assert_called_once()
            session.assert_called_once()

    def test_quota_exhaustion_or_disabled_account_stops_without_a_second_session(self):
        for error, text in (('RESOURCE_EXHAUSTED', ''), ('This service has been disabled in this account', ''),
                            ('', 'Remaining quota: 0%')):
            with self.subTest(error=error, text=text), self.environment() as (root, clock), \
                    patch.object(checks, 'observe_official_status', return_value=self.usage(response=text, error=error)), \
                    patch.object(checks, 'observe_official_session') as session:
                report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
                self.assertIn(report['status'], {'QUOTA_EXHAUSTED', 'SERVICE_DISABLED'})
                self.assertFalse(report['refreshAfterExpiryVerified'])
                session.assert_not_called()

    def test_a_connection_without_expiry_advancement_does_not_prove_refresh(self):
        with self.environment() as (root, clock), patch.object(checks, 'observe_official_status', return_value=self.usage()), \
                patch.object(checks, 'observe_official_session', return_value=self.session()):
            report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertFalse(report['refreshAfterExpiryVerified'])

    def test_expiry_advancement_without_oauth_connection_does_not_prove_refresh(self):
        with self.environment() as (root, clock):
            def status(*args):
                self.credential(root, (clock.wall + timedelta(hours=1)).isoformat())
                return self.usage(connections=0)
            with patch.object(checks, 'observe_official_status', side_effect=status), \
                    patch.object(checks, 'observe_official_session', return_value=self.session()):
                report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertFalse(report['refreshAfterExpiryVerified'])

    def test_changed_credentials_during_the_wait_stop_before_network(self):
        with self.environment() as (root, clock):
            def sleep(seconds):
                clock.sleep(seconds)
                self.credential(root, (clock.wall + timedelta(hours=1)).isoformat())
            with patch.object(refresh.time, 'sleep', side_effect=sleep), patch.object(checks, 'observe_official_status') as status:
                report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertEqual('CREDENTIAL_EXPIRY_CHANGED_WHILE_WAITING', report['status'])
            status.assert_not_called()

    def test_clock_jump_and_insufficient_wait_budget_stop_before_network(self):
        for scenario in ('clock_jump', 'budget', 'expired'):
            with self.subTest(scenario=scenario), self.environment() as (root, clock):
                if scenario == 'expired':
                    self.credential(root, clock.wall.isoformat())
                def sleep(seconds):
                    clock.sleep(seconds)
                    if scenario == 'clock_jump':
                        clock.wall += timedelta(seconds=10)
                with patch.object(refresh.time, 'sleep', side_effect=sleep), patch.object(checks, 'observe_official_status') as status:
                    report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True,
                                                           max_wait_seconds=1 if scenario == 'budget' else 60)
                self.assertIn(report['status'], {'CLOCK_DISCONTINUITY_OBSERVED', 'NATURAL_EXPIRY_EXCEEDS_WAIT_BUDGET',
                                                 'EXPIRY_ALREADY_ELAPSED_BEFORE_OBSERVATION'})
                status.assert_not_called()

    def test_protected_file_changes_invalidate_otherwise_valid_refresh(self):
        with self.environment() as (root, clock):
            def status(*args):
                self.credential(root, (clock.wall + timedelta(hours=1)).isoformat())
                pilot.private_json(root / 'policy.json', {'changed': True})
                return self.usage()
            with patch.object(checks, 'observe_official_status', side_effect=status), \
                    patch.object(checks, 'observe_official_session', return_value=self.session()) as session:
                report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertEqual('ISOLATION_GUARD_CHANGED', report['status'])
            self.assertFalse(report['refreshAfterExpiryVerified'])
            session.assert_not_called()

    def test_policy_changed_while_waiting_stops_before_any_cli_request(self):
        with self.environment() as (root, clock):
            def sleep(seconds):
                clock.sleep(seconds)
                pilot.private_json(root / 'policy.json', {'changed': True})
            with patch.object(refresh.time, 'sleep', side_effect=sleep), patch.object(checks, 'observe_official_status') as status:
                report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertEqual('ISOLATION_GUARD_CHANGED', report['status'])
            status.assert_not_called()

    def test_clock_jump_during_official_cli_checks_cannot_verify_refresh(self):
        with self.environment() as (root, clock):
            def status(*args):
                clock.wall += timedelta(seconds=10)
                self.credential(root, (clock.wall + timedelta(hours=1)).isoformat())
                return self.usage()
            with patch.object(checks, 'observe_official_status', side_effect=status), \
                    patch.object(checks, 'observe_official_session', return_value=self.session()):
                report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertEqual('CLOCK_DISCONTINUITY_OBSERVED', report['status'])
            self.assertFalse(report['refreshAfterExpiryVerified'])

    def test_session_failures_or_non_pro_tiers_cannot_prove_refresh(self):
        for updates in ({'subscriptionTier': 'FREE'}, {'modelId': 'another-model'}, {'generationRequests': 1},
                        {'modelSwitches': 1}, {'fallbacks': 1}, {'status': 'QUOTA_EXHAUSTED'}, {'currentModelMatches': False}):
            with self.subTest(updates=updates), self.environment() as (root, clock):
                def status(*args):
                    self.credential(root, (clock.wall + timedelta(hours=1)).isoformat())
                    return self.usage()
                with patch.object(checks, 'observe_official_status', side_effect=status), \
                        patch.object(checks, 'observe_official_session', return_value=self.session(**updates)):
                    report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
                self.assertFalse(report['refreshAfterExpiryVerified'])

    def test_exceptions_are_sanitized_and_a_new_command_can_reacquire_the_lock(self):
        with self.environment() as (root, clock), \
                patch.object(checks, 'observe_official_status', side_effect=pilot.PilotError('private-secret')):
            report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertEqual('NATURAL_EXPIRY_CHECK_STOPPED', report['status'])
            self.assertNotIn('private-secret', json.dumps(report))
            with pilot.pilot_lock(root, existing=True):
                pass

    def test_cancellation_during_wait_records_failure_and_releases_lock_without_network(self):
        with self.environment() as (root, clock), patch.object(refresh.time, 'sleep', side_effect=KeyboardInterrupt), \
                patch.object(checks, 'observe_official_status') as status:
            report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertEqual('NATURAL_EXPIRY_CHECK_STOPPED', report['status'])
            self.assertFalse(report['refreshAfterExpiryVerified'])
            status.assert_not_called()
            with pilot.pilot_lock(root, existing=True):
                pass

    def test_unverified_or_other_account_baseline_stops_before_opening_credentials(self):
        for updates in ({'subscriptionTier': 'FREE'}, {'modelId': 'another-model'},
                        {'officialAcpSessionVerified': False}, {'canContinueAcceptance': False}):
            with self.subTest(updates=updates), self.environment() as (root, clock):
                destination = root / 'reports/account-verification.json'
                original = pilot.read_private_json(destination)
                pilot.private_json(destination, {**original, **updates})
                with patch.object(refresh, 'read_isolated_expiry') as read, patch.object(checks, 'observe_official_status') as status:
                    report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
                self.assertEqual('CURRENT_ISOLATED_PRO_ACCOUNT_REQUIRED', report['status'])
                read.assert_not_called()
                status.assert_not_called()

    def test_supplied_refresh_success_flags_are_not_used_as_evidence(self):
        with self.environment() as (root, clock), \
                patch.object(checks, 'observe_official_status', return_value={**self.usage(connections=0),
                             'refreshAfterExpiryVerified': True, 'naturalExpiryObservationStarted': True,
                             'postRefreshExpiry': '2026-11-26T09:00:00Z', 'token': 'private-secret'}), \
                patch.object(checks, 'observe_official_session', return_value={**self.session(),
                             'refreshAfterExpiryVerified': True, 'expiry': '2026-11-26T09:00:00Z'}):
            report = refresh.verify_natural_refresh(root, '', 'gemini-fixed', True)
            self.assertFalse(report['refreshAfterExpiryVerified'])
            self.assertNotIn('private-secret', json.dumps(report))


if __name__ == '__main__':
    unittest.main()

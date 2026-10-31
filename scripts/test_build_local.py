"""Failure-path checks for the local release runner; never access a real account."""
import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('build_local', Path(__file__).with_name('build-local.py'))
build_local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_local)


class LocalBuildTests(unittest.TestCase):
    def make_build(self, folder, *extra):
        config = [] if '--config' in extra else ['--no-config']
        return build_local.Build(build_local.parser().parse_args(['--output', folder, *config, *extra]))

    def write_config(self, folder, config):
        path = Path(folder) / 'release.json'
        path.write_text(json.dumps(config), encoding='utf-8-sig')
        return str(path)

    def test_configuration_defaults_and_explicit_cli_overrides(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.write_config(folder, {'build': {
                'targets': ['android'], 'sign': True, 'notify_telegram': True,
                'skip_tests': True, 'command_timeout': 123},
                'env': {'ALIAS': 'release', 'KEY_STORE_PASSWORD': 'secret'}})
            with patch.dict(os.environ, {}, clear=True):
                build = self.make_build(folder, '--config', path)
                self.assertEqual(build.args.targets, ['android'])
                self.assertTrue(build.args.sign and build.args.notify_telegram)
                self.assertEqual(build.args.command_timeout, 123)
                self.assertEqual(build.env['KEY_ALIAS'], 'release')
                self.assertEqual(build.env['KEY_STORE_PASSWORD'], 'secret')
                overridden = self.make_build(folder, '--config', path, '--targets', 'desktop',
                                             '--no-sign', '--no-notify-telegram', '--no-skip-tests',
                                             '--command-timeout', '45')
                overridden.validate_options()
                self.assertEqual(overridden.args.targets, ['desktop'])
                self.assertFalse(overridden.args.sign or overridden.args.notify_telegram or overridden.args.skip_tests)
                self.assertEqual(overridden.args.command_timeout, 45)

    def test_environment_overrides_file_including_alias_synonyms(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.write_config(folder, {'env': {'KEY_ALIAS': 'file-alias',
                'ALIAS': 'file-other-alias', 'KEY_STORE_PASSWORD': 'file-password',
                'KEY_PASSWORD': 'file-key-password', 'JAVA_HOME': 'jdk', 'ANDROID_HOME': 'sdk'}})
            with patch.dict(os.environ, {'ALIAS': 'env-alias', 'KEY_STORE_PASSWORD': 'env-password',
                                         'KEY_PASSWORD': '', 'PATH': 'original-path'}, clear=True):
                build = self.make_build(folder, '--config', path)
            self.assertEqual(build.env['KEY_ALIAS'], 'env-alias')
            self.assertEqual(build.env['KEY_STORE_PASSWORD'], 'env-password')
            self.assertEqual(build.env['KEY_PASSWORD'], 'file-key-password')
            self.assertEqual(build.env['ANDROID_HOME'], str((Path(folder) / 'sdk').resolve()))
            self.assertTrue(build.env['PATH'].startswith(str((Path(folder) / 'jdk/bin').resolve())))

    def test_missing_default_is_optional_but_explicit_file_must_exist(self):
        with tempfile.TemporaryDirectory() as folder:
            missing = Path(folder) / 'missing.json'
            with patch.object(build_local, 'DEFAULT_CONFIG', missing):
                self.assertEqual(build_local.load_config(None), ({}, None))
                with self.assertRaisesRegex(RuntimeError, 'Cannot read configuration'):
                    build_local.load_config(missing)

    def test_default_file_loaded_and_no_config_bypasses_it(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.write_config(folder, {'build': {'sign': True}})
            with patch.object(build_local, 'DEFAULT_CONFIG', Path(path)):
                args = build_local.parser().parse_args([])
                self.assertTrue(build_local.Build(args).args.sign)
                self.assertFalse(self.make_build(folder).args.sign)

    def test_preflight_uses_configured_java_even_when_not_on_parent_path(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            path = self.write_config(folder, {'env': {'JAVA_HOME': 'jdk'}})
            build = self.make_build(folder, '--config', path)
            with patch.object(build, 'run', side_effect=RuntimeError('toolchain reached')) as run:
                with self.assertRaisesRegex(RuntimeError, 'toolchain reached'):
                    build.configure()
            java = Path(folder) / 'jdk/bin' / ('java.exe' if os.name == 'nt' else 'java')
            run.assert_called_once_with([java.resolve(), '-version'], capture=True)

    def test_invalid_configuration_is_rejected_without_echoing_contents(self):
        invalid = ['{"env":{"TELEGRAM_BOT_TOKEN":"sensitive",}}',
                   '{"env":{},"env":{"TELEGRAM_BOT_TOKEN":"sensitive"}}',
                   '{"build":{"sign":"sensitive"}}', '{"build":{"targets":[]}}',
                   '{"build":{"command_timeout":true}}', '{"build":{"notify_telegarm":true}}',
                   '{"env":{"TELEGRAM_BOT_TOKEN":123}}', '{"env":{"UNSUPPORTED":"sensitive"}}']
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'invalid.json'
            for content in invalid:
                with self.subTest(content=content):
                    path.write_text(content, encoding='utf-8')
                    build = self.make_build(folder, '--config', str(path))
                    with self.assertRaises(RuntimeError) as caught:
                        build.validate_options()
                    self.assertNotIn('sensitive', str(caught.exception))

    def test_invalid_config_saved_as_preflight_failure_without_starting_build(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.write_config(folder, {'build': {'sign': 'invalid'}})
            build = self.make_build(str(Path(folder) / 'output'), '--config', path)
            build.console.stream = io.StringIO()
            with patch.object(build, 'desktop') as desktop, patch.object(build, 'collect_reports'):
                self.assertEqual(build.execute(), 1)
            desktop.assert_not_called()
            summary = json.loads((build.output / 'summary.json').read_text(encoding='utf-8'))
            self.assertEqual(summary['stages'][0]['name'], 'preflight')
            self.assertEqual(summary['status'], 'failed')

    def test_init_configuration_does_not_overwrite_credentials(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'local-build.json'
            with patch('sys.stdout', io.StringIO()):
                build_local.init_config(path)
            config = json.loads(path.read_text(encoding='utf-8'))
            self.assertTrue(config['build']['sign'] and config['build']['notify_telegram'])
            self.assertTrue(all(value == '' for value in config['env'].values()))
            path.write_text('existing-secret', encoding='utf-8')
            with self.assertRaises(FileExistsError):
                build_local.init_config(path)
            self.assertEqual(path.read_text(encoding='utf-8'), 'existing-secret')

    def test_missing_credentials_are_reported_together_before_building(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            build = self.make_build(folder, '--sign', '--notify-telegram')
            with patch.object(build, 'run') as run:
                with self.assertRaises(RuntimeError) as caught:
                    build.configure()
            run.assert_not_called()
            for name in ('SIGNING_KEY_BASE64', 'KEY_ALIAS', 'KEY_STORE_PASSWORD',
                         'TELEGRAM_BOT_TOKEN', 'TELEGRAM_CHAT_ID'):
                self.assertIn(name, str(caught.exception))

    def test_bad_signing_key_and_token_fail_before_toolchain_checks(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            path = self.write_config(folder, {'build': {'sign': True, 'notify_telegram': True}, 'env': {
                'SIGNING_KEY_BASE64': 'not-base64-secret', 'ALIAS': 'release',
                'KEY_STORE_PASSWORD': 'secret', 'TELEGRAM_BOT_TOKEN': 'invalid-token', 'TELEGRAM_CHAT_ID': '123'}})
            build = self.make_build(folder, '--config', path)
            with patch.object(build, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'Base64 keystore'):
                    build.configure()
                build.env['SIGNING_KEY_BASE64'] = base64.b64encode(b'key').decode()
                with self.assertRaisesRegex(RuntimeError, 'Invalid Telegram token'):
                    build.configure()
            run.assert_not_called()

    def test_configured_pipeline_uploads_verified_apk_and_records_acknowledgement(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            path = self.write_config(folder, {'build': {'targets': ['android'], 'sign': True,
                'notify_telegram': True}, 'env': {'SIGNING_KEY_BASE64': base64.b64encode(b'key').decode(),
                'ALIAS': 'release', 'KEY_STORE_PASSWORD': 'secret',
                'TELEGRAM_BOT_TOKEN': '123456:fake-token', 'TELEGRAM_CHAT_ID': '-100123'}})
            build = self.make_build(str(Path(folder) / 'output'), '--config', path)
            build.console.stream = io.StringIO()
            build.summary.update(ref='test', subject='test release', commit='abcdef12345',
                                 commit_count=1, version_name='1.0', version_code='1', dirty_worktree=True)
            calls = []

            def android():
                build.unsigned = build.output / 'UniTrading-1.0-abcdef-unsigned.apk'
                build.unsigned.write_bytes(b'unsigned')

            def command(args, **kwargs):
                if 'sign' in args:
                    self.assertEqual(args[args.index('--ks') + 1].read_bytes(), b'key')
                    args[args.index('--out') + 1].write_bytes(b'signed')
                    calls.append('sign')
                elif 'verify' in args:
                    calls.append('verify')

            def upload(args, **kwargs):
                calls.append('upload')
                self.assertEqual(calls, ['sign', 'verify', 'upload'])
                self.assertEqual(build.signed.read_bytes(), b'signed')
                self.assertNotIn('fake-token', ' '.join(args))
                self.assertIn('bot123456:fake-token/sendDocument', kwargs['input'])
                self.assertIn('chat_id=-100123', args)
                return subprocess.CompletedProcess(args, 0, '{"ok":true,"result":{"message_id":1}}', '')

            with patch.object(build, 'configure'), patch.object(build, 'android', side_effect=android), \
                    patch.object(build, 'android_tool', side_effect=lambda name: name), \
                    patch.object(build, 'run', side_effect=command), patch.object(build, 'collect_reports'), \
                    patch.object(build_local.subprocess, 'run', side_effect=upload) as send:
                self.assertEqual(build.execute(), 0)
            send.assert_called_once()
            summary_text = (build.output / 'summary.json').read_text(encoding='utf-8')
            self.assertEqual(json.loads(summary_text)['telegram']['status'], 'sent')
            self.assertNotIn('fake-token', summary_text)
            self.assertNotIn('secret', summary_text)
            self.assertNotIn('fake-token', build.console.stream.getvalue())

    def test_telegram_rejects_http_api_and_non_json_failures_without_leaking_response(self):
        with tempfile.TemporaryDirectory() as folder:
            for code, body in [(22, '{"ok":false,"description":"sensitive"}'),
                               (0, '{"ok":false}'), (0, 'sensitive invalid JSON'), (0, '[]')]:
                with self.subTest(code=code, body=body):
                    build = self.make_build(folder)
                    build.signed = Path(folder) / 'signed.apk'
                    build.env.update(TELEGRAM_BOT_TOKEN='123:token', TELEGRAM_CHAT_ID='123')
                    build.summary.update(ref='test', subject='test', version_name='1', version_code='1',
                                         commit='abcdef123', commit_count=1, dirty_worktree=False)
                    with patch.object(build_local.subprocess, 'run', return_value=
                                      subprocess.CompletedProcess([], code, body, 'sensitive')):
                        with self.assertRaisesRegex(RuntimeError, 'Telegram upload failed') as caught:
                            build.telegram()
                    self.assertNotIn('sensitive', str(caught.exception))
                    self.assertNotIn('telegram', build.summary)

    def test_failed_regression_records_failure_and_prevents_release(self):
        with tempfile.TemporaryDirectory() as folder:
            build = self.make_build(folder, '--sign', '--notify-telegram')
            with patch.object(build, 'configure'), \
                    patch.object(build, 'desktop', side_effect=RuntimeError('regression failed')), \
                    patch.object(build, 'android') as android, \
                    patch.object(build, 'sign') as sign, \
                    patch.object(build, 'telegram') as telegram, \
                    patch.object(build, 'collect_reports'):
                self.assertEqual(build.execute(), 1)
            android.assert_not_called()
            sign.assert_not_called()
            telegram.assert_not_called()
            summary = json.loads((Path(folder) / 'summary.json').read_text(encoding='utf-8'))
            self.assertEqual(summary['status'], 'failed')
            self.assertEqual(summary['stages'][-1]['status'], 'failed')

    def test_refuses_old_output_without_overwriting_it(self):
        with tempfile.TemporaryDirectory() as folder:
            existing = Path(folder) / 'old-signed.apk'
            existing.write_bytes(b'old')
            with self.assertRaisesRegex(RuntimeError, 'Output must be empty'):
                self.make_build(folder).execute()
            self.assertEqual(existing.read_bytes(), b'old')

    def test_signing_failure_removes_keystore_and_does_not_expose_password(self):
        with tempfile.TemporaryDirectory() as folder:
            build = self.make_build(folder, '--targets', 'android', '--sign')
            build.unsigned = Path(folder) / 'UniTrading-1.0-abcdef-unsigned.apk'
            build.unsigned.write_bytes(b'fake-apk')
            build.env.update(SIGNING_KEY_BASE64=base64.b64encode(b'private-key').decode(),
                             KEY_ALIAS='test', KEY_STORE_PASSWORD='test-secret', KEY_PASSWORD='test-key-secret')
            temporary_keys = []

            def command(args, **kwargs):
                self.assertNotIn('test-secret', ' '.join(str(x) for x in args))
                self.assertNotIn('test-key-secret', ' '.join(str(x) for x in args))
                if 'sign' in args:
                    keystore = args[args.index('--ks') + 1]
                    temporary_keys.append(keystore)
                    self.assertEqual(keystore.read_bytes(), b'private-key')
                    raise RuntimeError('signer rejected key')

            with patch.object(build, 'android_tool', side_effect=lambda name: name), \
                    patch.object(build, 'run', side_effect=command):
                with self.assertRaisesRegex(RuntimeError, 'signer rejected'):
                    build.sign()
            self.assertEqual(len(temporary_keys), 1)
            self.assertFalse(temporary_keys[0].exists())
            self.assertNotIn('signed_apk', build.summary['artifacts'])

    def test_explicit_ios_on_windows_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            build = self.make_build(folder, '--targets', 'ios')
            with patch.object(build_local.sys, 'platform', 'win32'):
                with self.assertRaisesRegex(RuntimeError, 'Apple Silicon Mac'):
                    build.configure()

    def test_rejected_signature_is_not_left_as_a_distributable(self):
        with tempfile.TemporaryDirectory() as folder:
            build = self.make_build(folder, '--targets', 'android', '--sign')
            build.unsigned = Path(folder) / 'UniTrading-1.0-abcdef-unsigned.apk'
            build.unsigned.write_bytes(b'unsigned')
            build.env.update(SIGNING_KEY_BASE64=base64.b64encode(b'key').decode(),
                             KEY_ALIAS='test', KEY_STORE_PASSWORD='secret', KEY_PASSWORD='')

            def command(args, **kwargs):
                if 'sign' in args:
                    args[args.index('--out') + 1].write_bytes(b'bad-signature')
                elif 'verify' in args:
                    raise RuntimeError('signature invalid')

            with patch.object(build, 'android_tool', side_effect=lambda name: name), \
                    patch.object(build, 'run', side_effect=command):
                with self.assertRaisesRegex(RuntimeError, 'signature invalid'):
                    build.sign()
            self.assertFalse(build.signed.exists())

    def test_telegram_requires_signing(self):
        with tempfile.TemporaryDirectory() as folder:
            build = self.make_build(folder, '--notify-telegram')
            with self.assertRaisesRegex(RuntimeError, 'requires --sign'):
                build.configure()

    def test_streamed_failure_keeps_full_log_and_returns_error(self):
        with tempfile.TemporaryDirectory() as folder:
            build = self.make_build(folder)
            build.console.stream = io.StringIO()
            build.console.terminal = build.console.color = False
            build.log = io.StringIO()
            command = [sys.executable, '-u', '-c',
                       "print('> Task :example:compile'); print('diagnostic detail'); "
                       "print('FAILURE: compilation failed'); raise SystemExit(7)"]
            with self.assertRaisesRegex(RuntimeError, '7'):
                build.run(command, label='Compile smoke check')
            self.assertIn('diagnostic detail', build.log.getvalue())
            self.assertIn('compilation failed', build.console.stream.getvalue())
            self.assertNotIn('\033[', build.console.stream.getvalue())

    def test_silent_child_still_times_out_with_stream_reader(self):
        with tempfile.TemporaryDirectory() as folder:
            build = self.make_build(folder)
            build.console.stream = io.StringIO()
            build.console.terminal = build.console.color = False
            build.log = io.StringIO()
            with self.assertRaises(subprocess.TimeoutExpired):
                build.run([sys.executable, '-c', 'import time; time.sleep(30)'], timeout=0.2)


if __name__ == '__main__':
    unittest.main()

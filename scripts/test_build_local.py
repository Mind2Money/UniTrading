"""Failure-path checks for the local release runner; never access a real account."""
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('build_local', Path(__file__).with_name('build-local.py'))
build_local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_local)


class LocalBuildTests(unittest.TestCase):
    def make_build(self, folder, *extra):
        return build_local.Build(build_local.parser().parse_args(['--output', folder, *extra]))

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


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Local counterpart of build.yml. Python 3.10+, no third-party dependencies."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('broker-api', 'demo-broker', 'tradovate-broker', 'composeApp')


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--targets', nargs='+', choices=['desktop', 'android', 'ios'],
                   default=['desktop', 'android'] + (['ios'] if sys.platform == 'darwin' else []))
    p.add_argument('--skip-tests', action='store_true', help='Explicitly build without regression tests')
    p.add_argument('--sign', action='store_true', help='Sign Android using local environment credentials')
    p.add_argument('--notify-telegram', action='store_true', help='Explicitly upload the signed APK to Telegram')
    p.add_argument('--output', type=Path, help='New, empty output directory; default artifacts/local/<timestamp>')
    p.add_argument('--dry-run', action='store_true', help='Print stages without running commands or requiring secrets')
    p.add_argument('--notify-thread', help='Queue exactly one Codex message after completion, including failure')
    p.add_argument('--command-timeout', type=int, default=2700, help='Maximum seconds per build command (default 2700)')
    p.add_argument('--console-log', type=Path, help=argparse.SUPPRESS)
    return p


class Build:
    def __init__(self, args):
        self.args = args
        self.env = os.environ.copy()
        self.output = (args.output or ROOT / 'artifacts' / 'local' /
                       datetime.now().strftime('%Y%m%d-%H%M%S-%f')).resolve()
        self.summary = {'status': 'running', 'started_at': datetime.now(timezone.utc).isoformat(),
                        'targets': args.targets, 'tests': 'skipped' if args.skip_tests else 'requested',
                        'stages': [], 'artifacts': {}}
        self.log = None
        self.gradle = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))

    def run(self, command, *, capture=False, timeout=None, env=None):
        # Never log environment variables: passwords/tokens are passed only that way.
        command = [str(x) for x in command]
        self.log.write('\n> ' + ' '.join(command) + '\n')
        self.log.flush()
        result = subprocess.run(command, cwd=ROOT, env=env or self.env, timeout=timeout or self.args.command_timeout,
                                stdout=subprocess.PIPE if capture else self.log,
                                stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        if capture:
            self.log.write(result.stdout)
            self.log.flush()
        if result.returncode:
            raise RuntimeError(f'{Path(command[0]).name} exited {result.returncode}; see build.log')
        return result.stdout if capture else None

    def stage(self, name, action):
        item = {'name': name, 'status': 'running', 'started_at': datetime.now(timezone.utc).isoformat()}
        self.summary['stages'].append(item)
        print(name, flush=True)
        try:
            action()
            item['status'] = 'passed'
        except Exception:
            item['status'] = 'failed'
            raise
        finally:
            item['finished_at'] = datetime.now(timezone.utc).isoformat()

    def configure(self):
        if 'ios' in self.args.targets and (sys.platform != 'darwin' or platform.machine() != 'arm64'):
            raise RuntimeError('iOS requires an Apple Silicon Mac with Xcode; it cannot be verified on Windows.')
        if self.args.sign and 'android' not in self.args.targets:
            raise RuntimeError('--sign requires the android target')
        if self.args.notify_telegram and not self.args.sign:
            raise RuntimeError('--notify-telegram requires --sign')
        if self.args.sign:
            self.env['KEY_ALIAS'] = self.env.get('KEY_ALIAS') or self.env.get('ALIAS', '')
            for name in ('SIGNING_KEY_BASE64', 'KEY_ALIAS', 'KEY_STORE_PASSWORD'):
                if not self.env.get(name):
                    raise RuntimeError(f'Missing signing environment variable: {name}')
        if self.args.notify_telegram:
            for name in ('TELEGRAM_BOT_TOKEN', 'TELEGRAM_CHAT_ID'):
                if not self.env.get(name):
                    raise RuntimeError(f'Missing environment variable: {name}')
            if not shutil.which('curl.exe' if os.name == 'nt' else 'curl'):
                raise RuntimeError('curl is required for Telegram uploads')
        # Respect an explicitly chosen JDK. Gradle can also discover its JDK 21 toolchain.
        self.run(['java', '-version'])
        sdk = self.env.get('ANDROID_HOME') or self.env.get('ANDROID_SDK_ROOT')
        local = ROOT / 'local.properties'
        if local.exists():
            match = re.search(r'^sdk\.dir\s*=\s*(.+)$', local.read_text(), re.M)
            if match:
                sdk = match[1].strip().replace('\\:', ':').replace('\\\\', '\\')
        if not sdk or not Path(sdk).is_dir():
            raise RuntimeError('Set Android SDK in local.properties (sdk.dir) or ANDROID_HOME.')
        self.sdk = Path(sdk).resolve()
        self.env['ANDROID_HOME'] = str(self.sdk)
        self.env['ANDROID_SDK_ROOT'] = str(self.sdk)
        self.summary['commit'] = self.run(['git', 'rev-parse', 'HEAD'], capture=True).strip()
        self.summary['dirty_worktree'] = bool(self.run(['git', 'status', '--porcelain'], capture=True).strip())
        self.summary['subject'] = self.run(['git', 'log', '-1', '--pretty=%s'], capture=True).strip()
        self.summary['ref'] = self.run(['git', 'rev-parse', '--abbrev-ref', 'HEAD'], capture=True).strip()
        tags = self.run(['git', 'tag', '--list', 'v*', '--sort=-creatordate'], capture=True).splitlines()
        revision = tags[0] + '..HEAD' if tags else 'HEAD'
        self.summary['commit_count'] = int(self.run(['git', 'rev-list', '--count', revision], capture=True))

    def gradle_tasks(self, *tasks):
        self.run([self.gradle, '--console=plain', '--stacktrace', *tasks])

    def desktop(self):
        if not self.args.skip_tests:
            # Rerun test tasks even if Gradle considers previous results current.
            self.gradle_tasks(*[f':{m}:desktopTest' for m in MODULES], '--rerun-tasks')
        self.gradle_tasks(':composeApp:createDistributable')
        source = ROOT / 'composeApp/build/compose/binaries/main/app'
        if not source.is_dir():
            raise RuntimeError('Desktop distributable was not produced')
        shutil.copytree(source, self.output / 'desktop')
        self.summary['artifacts']['desktop'] = 'desktop'

    def android_tool(self, name):
        suffix = ('.bat' if name == 'apksigner' else '.exe') if os.name == 'nt' else ''
        candidates = list((self.sdk / 'build-tools').glob(f'*/{name}{suffix}'))
        if not candidates:
            raise RuntimeError(f'Install Android SDK build-tools ({name} missing)')
        return max(candidates, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)))

    def android(self):
        # Local outputs may belong to credentialStoreTest/instrumentation builds. Clean
        # this module so stale APK/maps cannot be mistaken for production artifacts.
        self.gradle_tasks(':androidApp:clean')
        self.gradle_tasks(':androidApp:assembleRelease', '-PcredentialStoreTest=false',
                          '-PinstrumentationBuildType=debug')
        outputs = ROOT / 'androidApp/build/outputs'
        for name in ('mapping.txt', 'resources.txt'):
            source = outputs / 'mapping/release' / name
            if not source.is_file() or not source.stat().st_size:
                raise RuntimeError(f'R8 output missing: {source}')
            shutil.copy2(source, self.output / name)
        apk = outputs / 'apk/release/androidApp-release-unsigned.apk'
        badging = self.run([self.android_tool('aapt2'), 'dump', 'badging', apk], capture=True)
        values = {k: re.search(rf"{k}='([^']+)'", badging) for k in ('versionName', 'versionCode')}
        if not all(values.values()) or "package: name='com.unitrading.demo'" not in badging:
            raise RuntimeError('Unexpected APK application ID/version; refusing to publish')
        version, code = values['versionName'][1], values['versionCode'][1]
        short_sha = self.run(['git', 'rev-parse', '--short', 'HEAD'], capture=True).strip()
        name = f'UniTrading-{version}-{short_sha}'
        self.unsigned = self.output / f'{name}-unsigned.apk'
        shutil.copy2(apk, self.unsigned)
        self.summary.update(version_name=version, version_code=code)
        self.summary['artifacts'].update(unsigned_apk=self.unsigned.name, mapping='mapping.txt', resources='resources.txt')

    def sign(self):
        self.signed = self.output / self.unsigned.name.replace('-unsigned.apk', '.apk')
        self.run([self.android_tool('zipalign'), '-c', '4', self.unsigned])
        with tempfile.TemporaryDirectory(prefix='unitrading-sign-') as folder:
            keystore = Path(folder) / 'release.keystore'
            candidate = Path(folder) / 'signed.apk'
            keystore.write_bytes(base64.b64decode(re.sub(r'\s+', '', self.env['SIGNING_KEY_BASE64']), validate=True))
            keystore.chmod(0o600)
            command = [self.android_tool('apksigner'), 'sign', '--ks', keystore,
                       '--ks-key-alias', self.env['KEY_ALIAS'], '--ks-pass', 'env:KEY_STORE_PASSWORD',
                       '--v4-signing-enabled', 'false']
            if self.env.get('KEY_PASSWORD'):
                command += ['--key-pass', 'env:KEY_PASSWORD']
            self.run([*command, '--out', candidate, self.unsigned])
            self.run([self.android_tool('apksigner'), 'verify', '--print-certs', candidate])
            # Only verified signatures receive the final distributable filename.
            shutil.copy2(candidate, self.signed)
        self.summary['artifacts']['signed_apk'] = self.signed.name

    def ios(self):
        if not self.args.skip_tests:
            self.gradle_tasks(*[f':{m}:iosSimulatorArm64Test' for m in MODULES], '--rerun-tasks')
            self.env['IOS_ARTIFACT_DIR'] = str(self.output / 'ios')
            self.run(['bash', ROOT / 'scripts/test-ios.sh'])
        else:
            self.run(['xcodebuild', '-project', 'iosApp/iosApp.xcodeproj', '-scheme', 'iosApp',
                      '-sdk', 'iphonesimulator', '-configuration', 'Debug', '-destination',
                      'generic/platform=iOS Simulator', '-derivedDataPath', self.output / 'ios/ios-build',
                      'ARCHS=arm64', 'ONLY_ACTIVE_ARCH=YES', 'CODE_SIGNING_ALLOWED=NO', 'build'])
        self.summary['artifacts']['ios'] = 'ios'

    def telegram(self):
        # Token is provided to curl through stdin config, never in argv, logs, or files.
        token = self.env['TELEGRAM_BOT_TOKEN']
        if not re.fullmatch(r'[0-9]+:[A-Za-z0-9_-]+', token):
            raise RuntimeError('Invalid Telegram token format')
        caption = (f"UniTrading | {self.summary['ref']}\n{self.summary['subject'][:300]}\n"
                   f"{self.summary['version_name']} ({self.summary['version_code']}) | "
                   f"{self.summary['commit'][:7]} | +{self.summary['commit_count']} commits\n"
                   f"Local build; dirty worktree: {self.summary['dirty_worktree']}\nSigned, R8-minified APK")
        caption_file = self.output / 'telegram-caption.txt'
        caption_file.write_text(caption, encoding='utf-8')
        command = ['curl.exe' if os.name == 'nt' else 'curl', '--config', '-', '--silent', '--show-error',
                   '--fail-with-body', '--connect-timeout', '15', '--max-time', '180',
                   '--form-string', 'chat_id=' + self.env['TELEGRAM_CHAT_ID'],
                   '--form', f'document=@"{self.signed.as_posix()}"',
                   '--form', f'caption=<"{caption_file.as_posix()}"']
        result = subprocess.run(command, input=f'url = "https://api.telegram.org/bot{token}/sendDocument"\n',
                                text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=200, cwd=ROOT)
        # A response can contain chat information; keep only the acknowledgement.
        if result.returncode or not json.loads(result.stdout).get('ok'):
            raise RuntimeError('Telegram upload failed (response omitted to protect credentials/chat data)')

    def collect_reports(self):
        for module in MODULES:
            for relative in ('reports/tests', 'test-results'):
                source = ROOT / module / 'build' / relative
                if source.is_dir():
                    shutil.copytree(source, self.output / 'reports' / module / relative, dirs_exist_ok=True)

    def execute(self):
        if self.output.exists() and any(self.output.iterdir()):
            raise RuntimeError(f'Output must be empty to prevent stale artifacts: {self.output}')
        self.output.mkdir(parents=True, exist_ok=True)
        code = 1
        with (self.output / 'build.log').open('w', encoding='utf-8', buffering=1) as self.log:
            try:
                self.stage('preflight', self.configure)
                for target in dict.fromkeys(self.args.targets):
                    self.stage(target, getattr(self, target))
                if self.args.sign:
                    self.stage('sign-android', self.sign)
                if self.args.notify_telegram:
                    self.stage('telegram', self.telegram)
                self.summary['status'] = 'passed'
                code = 0
            except Exception as error:
                self.summary['status'] = 'failed'
                self.summary['error'] = str(error)
                print(str(error), file=sys.stderr)
            finally:
                try:
                    self.collect_reports()
                except Exception as error:
                    self.summary['report_collection_error'] = str(error)
                    self.summary['status'] = 'failed'
                    code = 1
                self.summary['finished_at'] = datetime.now(timezone.utc).isoformat()
                self.summary['sha256'] = {}
                for apk in self.output.glob('*.apk'):
                    digest = hashlib.sha256()
                    with apk.open('rb') as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                            digest.update(chunk)
                    self.summary['sha256'][apk.name] = digest.hexdigest()
                (self.output / 'summary.json').write_text(json.dumps(self.summary, indent=2, ensure_ascii=False), encoding='utf-8')
        print(f"{self.summary['status']}: {self.output}", flush=True)
        return code


def main():
    args = parser().parse_args()
    if args.command_timeout <= 0:
        parser().error('--command-timeout must be positive')
    if args.console_log:
        args.console_log.parent.mkdir(parents=True, exist_ok=True)
        # Detached Windows processes must not depend on a parent's console handles.
        sys.stdout = sys.stderr = args.console_log.open('w', encoding='utf-8', buffering=1)
    build = Build(args)
    if args.dry_run:
        print(json.dumps({'targets': args.targets, 'tests': not args.skip_tests, 'sign': args.sign,
                          'telegram': args.notify_telegram, 'output': str(build.output)}, indent=2))
        return 0
    code = 1
    try:
        code = build.execute()
    except Exception as error:
        print(str(error), file=sys.stderr)
    finally:
        if args.notify_thread:
            # One completion event, no polling, retry loop, or scheduled wakeups.
            message = (f'Local CI finished with exit code {code}. Read {build.output / "summary.json"} '
                       f'and {build.output / "build.log"}, then report the result. '
                       'Do not poll or rerun successful builds.')
            try:
                command = shutil.which('codex')
                ps_script = Path(os.environ.get('APPDATA', '')) / 'npm/codex.ps1'
                if os.name == 'nt' and ps_script.is_file():
                    argv = ['pwsh', '-NoProfile', '-File', str(ps_script)]
                elif command:
                    argv = [command]
                else:
                    raise RuntimeError('Codex CLI not found; results remain on disk')
                result = subprocess.run([*argv, 'queue', '--thread', args.notify_thread, '--message', message],
                                        text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=30, cwd=ROOT)
                (build.output / 'callback.log').write_text(result.stdout + result.stderr, encoding='utf-8')
                if result.returncode:
                    print('Completion callback failed; see callback.log. Results remain on disk.', file=sys.stderr)
            except Exception as error:
                if build.output.is_dir():
                    (build.output / 'callback.log').write_text(str(error), encoding='utf-8')
                print(f'Completion callback unavailable: {error}; result directory: {build.output}', file=sys.stderr)
    return code


if __name__ == '__main__':
    sys.exit(main())

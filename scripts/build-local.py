#!/usr/bin/env python3
"""Local counterpart of build.yml. Python 3.10+, no third-party dependencies."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import queue
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('broker-api', 'demo-broker', 'tradovate-broker', 'composeApp')
STAGES = {'preflight': '环境检查', 'desktop': '桌面测试与打包', 'android': 'Android R8 编译',
          'ios': 'iOS 构建与检查', 'sign-android': 'Android 签名', 'telegram': 'Telegram 发布'}
DEFAULT_CONFIG = ROOT / 'local-build.json'
BUILD_DEFAULTS = {
    'targets': ['desktop', 'android'] + (['ios'] if sys.platform == 'darwin' else []),
    'skip_tests': False, 'sign': False, 'notify_telegram': False, 'command_timeout': 2700,
}
CONFIG_ENV_KEYS = ('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'SIGNING_KEY_BASE64',
                   'ALIAS', 'KEY_ALIAS', 'KEY_STORE_PASSWORD', 'KEY_PASSWORD',
                   'TELEGRAM_BOT_TOKEN', 'TELEGRAM_CHAT_ID')


def load_config(path, disabled=False):
    if disabled:
        return {}, None
    selected = (path or DEFAULT_CONFIG).resolve()
    if path is None and not selected.exists():
        return {}, None

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate configuration key')
            result[key] = value
        return result

    try:
        config = json.loads(selected.read_text(encoding='utf-8-sig'), object_pairs_hook=unique_object)
    except (OSError, ValueError):
        # Do not include JSON input or decoding errors: they may contain secrets.
        raise RuntimeError(f'Cannot read configuration as unique-key UTF-8 JSON: {selected}') from None
    if not isinstance(config, dict) or config.keys() - {'build', 'env'}:
        raise RuntimeError('Configuration must be an object containing only build and env')
    options = config.get('build', {})
    environment = config.get('env', {})
    if not isinstance(options, dict) or options.keys() - BUILD_DEFAULTS.keys():
        raise RuntimeError('Unknown build configuration option; see docs/local-build.md')
    for name, value in options.items():
        if name == 'targets':
            valid = isinstance(value, list) and bool(value) and all(
                isinstance(target, str) and target in ('desktop', 'android', 'ios') for target in value)
        elif name == 'command_timeout':
            valid = type(value) is int and value > 0
        else:
            valid = type(value) is bool
        if not valid:
            raise RuntimeError(f'Invalid type or value for build.{name}')
    if not isinstance(environment, dict) or environment.keys() - set(CONFIG_ENV_KEYS):
        raise RuntimeError('Unknown env configuration option; see docs/local-build.md')
    if any(not isinstance(value, str) or '\0' in value for value in environment.values()):
        raise RuntimeError('All env values must be strings without NUL characters')
    return config, selected


def init_config(path):
    selected = (path or DEFAULT_CONFIG).resolve()
    template = {'build': {**BUILD_DEFAULTS, 'sign': True, 'notify_telegram': True},
                'env': dict.fromkeys(CONFIG_ENV_KEYS, '')}
    # Exclusive creation preserves an existing credential file, even on repeated calls.
    descriptor = os.open(selected, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        json.dump(template, stream, indent=2, ensure_ascii=False)
        stream.write('\n')
    print(f'Created local release configuration: {selected}')
    print('Fill in signing and Telegram credentials locally before building. Do not commit this file.')


def duration(seconds):
    if seconds < 60:
        return f'{seconds:.1f} 秒'
    minutes, seconds = divmod(int(seconds), 60)
    hours, minutes = divmod(minutes, 60)
    return (f'{hours} 小时 ' if hours else '') + f'{minutes} 分 {seconds:02d} 秒'


class Console:
    """Compact live progress in a terminal; timestamped plain lines in log files."""
    COLORS = {'RUN': '36', 'OK': '32', 'FAIL': '31', 'WAIT': '33', 'INFO': '90'}

    def __init__(self):
        self.stream = sys.stdout
        self.terminal = self.stream.isatty() and os.environ.get('TERM') != 'dumb'
        if self.terminal and os.name == 'nt':
            # Enable ANSI handling for a native Windows console if necessary.
            import ctypes
            from ctypes import wintypes
            kernel = ctypes.WinDLL('kernel32', use_last_error=True)
            kernel.GetStdHandle.argtypes = [wintypes.DWORD]
            kernel.GetStdHandle.restype = wintypes.HANDLE
            kernel.GetConsoleMode.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
            kernel.SetConsoleMode.argtypes = [wintypes.HANDLE, wintypes.DWORD]
            handle = kernel.GetStdHandle(-11)
            mode = wintypes.DWORD()
            self.terminal = bool(kernel.GetConsoleMode(handle, ctypes.byref(mode)) and
                                 kernel.SetConsoleMode(handle, mode.value | 4))
        self.color = self.terminal and 'NO_COLOR' not in os.environ
        self.live = False

    def paint(self, text, code):
        return f'\033[{code}m{text}\033[0m' if self.color else text

    def clear(self):
        if self.live:
            print('\r\033[2K', end='', file=self.stream, flush=True)
            self.live = False

    def line(self, text='', status=None):
        self.clear()
        if status:
            stamp = datetime.now().astimezone().strftime('%H:%M:%S')
            text = f'{self.paint(stamp, "90")}  {self.paint(f"{status:<4}", self.COLORS[status])}  {text}'
        print(text, file=self.stream, flush=True)

    def progress(self, text):
        if not self.terminal:
            self.line(text, 'RUN')
            return
        self.clear()
        # CJK characters take two terminal cells; avoid wrapping a live status line.
        import unicodedata
        limit = max(20, shutil.get_terminal_size().columns - 2)
        line = datetime.now().astimezone().strftime('%H:%M:%S') + '  RUN   ' + text
        clipped, width = '', 0
        for char in line:
            width += 2 if unicodedata.east_asian_width(char) in ('W', 'F') else 1
            if width > limit - 3:
                clipped += '...'
                break
            clipped += char
        print('\r' + self.paint(clipped, '36'), end='', file=self.stream, flush=True)
        self.live = True


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    config = p.add_mutually_exclusive_group()
    config.add_argument('--config', type=Path, help='Configuration JSON; default <repo>/local-build.json if present')
    config.add_argument('--no-config', action='store_true', help='Ignore the default configuration file')
    p.add_argument('--init-config', action='store_true', help='Create a local release configuration without building')
    p.add_argument('--targets', nargs='+', choices=['desktop', 'android', 'ios'])
    p.add_argument('--skip-tests', action=argparse.BooleanOptionalAction, default=None,
                   help='Explicitly build without regression tests')
    p.add_argument('--sign', action=argparse.BooleanOptionalAction, default=None,
                   help='Sign Android using configuration/environment credentials')
    p.add_argument('--notify-telegram', action=argparse.BooleanOptionalAction, default=None,
                   help='Upload the signed APK to Telegram')
    p.add_argument('--output', type=Path, help='New, empty output directory; default artifacts/local/<timestamp>')
    p.add_argument('--dry-run', action='store_true', help='Print stages without running commands or requiring secrets')
    p.add_argument('--notify-thread', help='Queue exactly one Codex message after completion, including failure')
    p.add_argument('--command-timeout', type=int, help='Maximum seconds per build command (default 2700)')
    p.add_argument('--console-log', type=Path, help=argparse.SUPPRESS)
    return p


class Build:
    def __init__(self, args):
        self.args = args
        self.env = os.environ.copy()
        self.config_path = None
        self.configuration_error = None
        try:
            config, self.config_path = load_config(args.config, args.no_config)
        except RuntimeError as error:
            # Record bad configuration as a preflight failure, including background callbacks.
            self.configuration_error = str(error)
            config = {}
        for name, default in BUILD_DEFAULTS.items():
            if getattr(args, name) is None:
                setattr(args, name, config.get('build', {}).get(name, default))
        configured_env = config.get('env', {})
        for name, value in configured_env.items():
            if value and not self.env.get(name):
                if name in ('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT'):
                    value = str((self.config_path.parent / Path(value).expanduser()).resolve())
                self.env[name] = value
        # Treat ALIAS and KEY_ALIAS as one setting across sources, not two competing defaults.
        self.env['KEY_ALIAS'] = (os.environ.get('KEY_ALIAS') or os.environ.get('ALIAS') or
                                 configured_env.get('KEY_ALIAS') or configured_env.get('ALIAS', ''))
        if self.env.get('JAVA_HOME'):
            self.env['PATH'] = str(Path(self.env['JAVA_HOME']) / 'bin') + os.pathsep + self.env.get('PATH', '')
        self.output = (args.output or ROOT / 'artifacts' / 'local' /
                       datetime.now().strftime('%Y%m%d-%H%M%S-%f')).resolve()
        self.summary = {'status': 'running', 'started_at': datetime.now(timezone.utc).isoformat(),
                        'targets': args.targets, 'tests': 'skipped' if args.skip_tests else 'requested',
                        'stages': [], 'artifacts': {}}
        self.log = None
        self.gradle = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
        self.console = Console()
        self.started = time.monotonic()
        self.stage_count = 1 + len(set(args.targets)) + int(args.sign) + int(args.notify_telegram)

    def banner(self, preview=False):
        self.console.line()
        self.console.line(self.console.paint('  UniTrading  /  ' + ('构建计划' if preview else '本地构建'), '1;36'))
        self.console.line('  ' + '-' * 62)
        self.console.line(f'  开始时间  {datetime.now().astimezone().isoformat(sep=" ", timespec="seconds")}')
        names = {'desktop': '桌面应用', 'android': 'Android APK', 'ios': 'iOS 模拟器'}
        self.console.line('  构建目标  ' + ' / '.join(names[t] for t in dict.fromkeys(self.args.targets)))
        self.console.line(f'  执行选项  测试：{"跳过" if self.args.skip_tests else "开启"}  '
                          f'签名：{"开启" if self.args.sign else "关闭"}  '
                          f'Telegram：{"开启" if self.args.notify_telegram else "关闭"}')
        self.console.line(f'  配置文件  {self.config_path or "未加载（命令行 / 环境变量）"}')
        self.console.line(f'  完整日志  {self.output / "build.log"}')
        self.console.line('  ' + '-' * 62)

    def show_result(self):
        self.console.line()
        passed = self.summary['status'] == 'passed'
        self.console.line(f'{"构建完成" if passed else "构建失败"} · 总耗时 {duration(time.monotonic() - self.started)}',
                          'OK' if passed else 'FAIL')
        for item in self.summary['stages']:
            self.console.line(f'  {STAGES.get(item["name"], item["name"])}  '
                              f'{"通过" if item["status"] == "passed" else "失败"}  '
                              f'{duration(item["duration_seconds"])}')
        if self.summary.get('error'):
            self.console.line('  失败原因  ' + self.summary['error'])
        if self.summary.get('report_collection_error'):
            self.console.line('  报告归档失败  ' + self.summary['report_collection_error'])
        if self.summary['artifacts']:
            self.console.line()
            self.console.line('  已生成产物')
            for name, relative in self.summary['artifacts'].items():
                labels = {'desktop': '桌面应用', 'unsigned_apk': '未签名 APK', 'signed_apk': '已签名 APK',
                          'mapping': 'R8 映射', 'resources': '资源压缩报告', 'ios': 'iOS 应用与日志'}
                self.console.line(f'  {labels.get(name, name)}  {self.output / relative}')
        self.console.line()
        self.console.line(f'  完整日志  {self.output / "build.log"}')
        self.console.line(f'  结果摘要  {self.output / "summary.json"}')
        self.console.line()

    def run(self, command, *, capture=False, timeout=None, env=None, label=None):
        # Never log environment variables: passwords/tokens are passed only that way.
        command = [str(x) for x in command]
        self.log.write('\n> ' + ' '.join(command) + '\n')
        self.log.flush()
        if capture:
            result = subprocess.run(command, cwd=ROOT, env=env or self.env, timeout=timeout or self.args.command_timeout,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding='utf-8', errors='replace')
            self.log.write(result.stdout)
            self.log.flush()
            if result.returncode:
                raise RuntimeError(f'{Path(command[0]).name} 退出码 {result.returncode}；详见 build.log')
            return result.stdout

        label = label or Path(command[0]).name
        self.console.line(label, 'RUN')
        started = time.monotonic()
        deadline = started + (timeout or self.args.command_timeout)
        lines = queue.Queue()
        # The pipe reader blocks on output; only this local UI emits a heartbeat.
        # No Codex wakeup or model polling is involved.
        with subprocess.Popen(command, cwd=ROOT, env=env or self.env, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace',
                              bufsize=1, start_new_session=os.name != 'nt') as process:
            def read_output():
                try:
                    for line in process.stdout:
                        lines.put(line)
                finally:
                    lines.put(None)

            reader = threading.Thread(target=read_output, daemon=True)
            reader.start()
            next_update = started + 15
            current_task = label
            error_lines = 0
            try:
                while True:
                    now = time.monotonic()
                    if now >= deadline:
                        raise subprocess.TimeoutExpired(command, timeout or self.args.command_timeout)
                    try:
                        line = lines.get(timeout=max(0, min(deadline, next_update) - now))
                    except queue.Empty:
                        self.console.progress(f'{current_task} · 已用 {duration(time.monotonic() - started)} · 仍在运行')
                        next_update = time.monotonic() + 15
                        continue
                    if line is None:
                        break
                    self.log.write(line)
                    self.log.flush()
                    text = line.strip()
                    if text.startswith('> Task ') and not text.endswith(('UP-TO-DATE', 'NO-SOURCE', 'SKIPPED')):
                        current_task = text.removeprefix('> Task ')
                        self.console.progress(f'{current_task} · 已用 {duration(time.monotonic() - started)}')
                    elif text.startswith(('FAILURE:', '* What went wrong:', 'e: ')) or text.endswith(' FAILED'):
                        error_lines = 8
                        self.console.line(text, 'FAIL')
                    elif error_lines and text:
                        self.console.line(text, 'FAIL')
                        error_lines -= 1
                    elif text.startswith(('BUILD SUCCESSFUL', '** BUILD SUCCEEDED **')) or 'SELF_TEST PASS' in text:
                        self.console.line(text, 'OK')
                    if time.monotonic() >= next_update:
                        self.console.progress(f'{current_task} · 已用 {duration(time.monotonic() - started)}')
                        next_update = time.monotonic() + 15
                code = process.wait(timeout=max(0.01, deadline - time.monotonic()))
                if code:
                    raise RuntimeError(f'{label}失败，退出码 {code}；详见 build.log')
            except BaseException:
                # A Gradle launcher can have Java children holding the output pipe.
                # Stop the command tree before joining the reader on timeout/error.
                if os.name == 'nt' and process.poll() is None:
                    try:
                        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                    except (OSError, subprocess.TimeoutExpired):
                        pass
                elif os.name != 'nt':
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                process.kill()
                process.wait()
                raise
            finally:
                self.console.clear()
                reader.join(timeout=1)
        self.console.line(f'{label}完成 · {duration(time.monotonic() - started)}', 'OK')

    def stage(self, name, action):
        item = {'name': name, 'status': 'running', 'started_at': datetime.now(timezone.utc).isoformat()}
        self.summary['stages'].append(item)
        title = STAGES.get(name, name)
        started = time.monotonic()
        self.console.line()
        self.console.line(f'[{len(self.summary["stages"])}/{self.stage_count}] {title}', 'RUN')
        try:
            action()
            item['status'] = 'passed'
        except Exception:
            item['status'] = 'failed'
            raise
        finally:
            item['finished_at'] = datetime.now(timezone.utc).isoformat()
            item['duration_seconds'] = round(time.monotonic() - started, 2)
            self.console.line(f'{title} · {duration(item["duration_seconds"])}',
                              'OK' if item['status'] == 'passed' else 'FAIL')

    def validate_options(self):
        if self.configuration_error:
            raise RuntimeError(self.configuration_error)
        if self.args.command_timeout <= 0:
            raise RuntimeError('--command-timeout must be positive')
        if 'ios' in self.args.targets and (sys.platform != 'darwin' or platform.machine() != 'arm64'):
            raise RuntimeError('iOS requires an Apple Silicon Mac with Xcode; it cannot be verified on Windows.')
        if self.args.sign and 'android' not in self.args.targets:
            raise RuntimeError('--sign requires the android target')
        if self.args.notify_telegram and not self.args.sign:
            raise RuntimeError('--notify-telegram requires --sign')

    def configure(self):
        self.validate_options()
        required = []
        if self.args.sign:
            self.env['KEY_ALIAS'] = self.env.get('KEY_ALIAS') or self.env.get('ALIAS', '')
            required.extend(('SIGNING_KEY_BASE64', 'KEY_ALIAS', 'KEY_STORE_PASSWORD'))
        if self.args.notify_telegram:
            required.extend(('TELEGRAM_BOT_TOKEN', 'TELEGRAM_CHAT_ID'))
        missing = [name for name in required if not self.env.get(name, '').strip()]
        if missing:
            raise RuntimeError('Missing release configuration (env in local-build.json or environment): ' +
                               ', '.join(missing))
        if self.args.sign:
            try:
                key = base64.b64decode(re.sub(r'\s+', '', self.env['SIGNING_KEY_BASE64']), validate=True)
                if not key:
                    raise ValueError()
            except ValueError:
                raise RuntimeError('SIGNING_KEY_BASE64 must contain a nonempty Base64 keystore') from None
        if self.args.notify_telegram:
            if not re.fullmatch(r'[0-9]+:[A-Za-z0-9_-]+', self.env['TELEGRAM_BOT_TOKEN']):
                raise RuntimeError('Invalid Telegram token format')
            if not shutil.which('curl.exe' if os.name == 'nt' else 'curl'):
                raise RuntimeError('curl is required for Telegram uploads')
        # Respect an explicitly chosen JDK. Gradle can also discover its JDK 21 toolchain.
        java = (Path(self.env['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
                if self.env.get('JAVA_HOME') else 'java')
        java_version = self.run([java, '-version'], capture=True).splitlines()
        if java_version:
            self.console.line(java_version[0], 'INFO')
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
        self.console.line(f'Android SDK  {self.sdk}', 'INFO')
        self.summary['commit'] = self.run(['git', 'rev-parse', 'HEAD'], capture=True).strip()
        self.summary['dirty_worktree'] = bool(self.run(['git', 'status', '--porcelain'], capture=True).strip())
        self.summary['subject'] = self.run(['git', 'log', '-1', '--pretty=%s'], capture=True).strip()
        self.summary['ref'] = self.run(['git', 'rev-parse', '--abbrev-ref', 'HEAD'], capture=True).strip()
        tags = self.run(['git', 'tag', '--list', 'v*', '--sort=-creatordate'], capture=True).splitlines()
        revision = tags[0] + '..HEAD' if tags else 'HEAD'
        self.summary['commit_count'] = int(self.run(['git', 'rev-list', '--count', revision], capture=True))
        self.console.line(f'{self.summary["ref"]} @ {self.summary["commit"][:7]}' +
                          (' · 含未提交改动' if self.summary['dirty_worktree'] else ''), 'INFO')

    def gradle_tasks(self, *tasks, label):
        self.run([self.gradle, '--console=plain', '--stacktrace', *tasks], label=label)

    def desktop(self):
        if not self.args.skip_tests:
            # Rerun test tasks even if Gradle considers previous results current.
            self.gradle_tasks(*[f':{m}:desktopTest' for m in MODULES], '--rerun-tasks', label='桌面回归测试（4 个模块）')
        self.gradle_tasks(':composeApp:createDistributable', label='生成桌面应用')
        source = ROOT / 'composeApp/build/compose/binaries/main/app'
        if not source.is_dir():
            raise RuntimeError('Desktop distributable was not produced')
        self.console.line('正在归档桌面应用…', 'INFO')
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
        self.gradle_tasks(':androidApp:clean', label='清理旧 Android 产物')
        self.gradle_tasks(':androidApp:assembleRelease', '-PcredentialStoreTest=false',
                          '-PinstrumentationBuildType=debug', label='编译 Release APK（R8 代码与资源压缩）')
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
        self.console.line(f'{self.unsigned.name} · {self.unsigned.stat().st_size / 1024 / 1024:.2f} MiB', 'INFO')

    def sign(self):
        self.signed = self.output / self.unsigned.name.replace('-unsigned.apk', '.apk')
        self.run([self.android_tool('zipalign'), '-c', '4', self.unsigned], label='检查 APK 对齐')
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
            self.run([*command, '--out', candidate, self.unsigned], label='签名 APK')
            self.run([self.android_tool('apksigner'), 'verify', '--print-certs', candidate], label='验证 APK 签名')
            # Only verified signatures receive the final distributable filename.
            shutil.copy2(candidate, self.signed)
        self.summary['artifacts']['signed_apk'] = self.signed.name

    def ios(self):
        if not self.args.skip_tests:
            self.gradle_tasks(*[f':{m}:iosSimulatorArm64Test' for m in MODULES], '--rerun-tasks', label='Kotlin Native 模拟器测试')
            self.env['IOS_ARTIFACT_DIR'] = str(self.output / 'ios')
            self.run(['bash', ROOT / 'scripts/test-ios.sh'], label='Xcode 构建及原生平台/UI 自检')
        else:
            self.run(['xcodebuild', '-project', 'iosApp/iosApp.xcodeproj', '-scheme', 'iosApp',
                      '-sdk', 'iphonesimulator', '-configuration', 'Debug', '-destination',
                      'generic/platform=iOS Simulator', '-derivedDataPath', self.output / 'ios/ios-build',
                      'ARCHS=arm64', 'ONLY_ACTIVE_ARCH=YES', 'CODE_SIGNING_ALLOWED=NO', 'build'], label='Xcode 模拟器应用构建')
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
                                text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=200,
                                cwd=ROOT, env=self.env)
        # A response can contain chat information; keep only the acknowledgement.
        try:
            response = json.loads(result.stdout)
        except ValueError:
            response = None
        if result.returncode or not isinstance(response, dict) or response.get('ok') is not True:
            raise RuntimeError('Telegram upload failed (response omitted to protect credentials/chat data)')
        self.summary['telegram'] = {'status': 'sent'}

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
        self.started = time.monotonic()
        self.banner()
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
                self.console.line(str(error), 'FAIL')
            finally:
                self.console.line('正在保存测试报告和产物校验信息…', 'INFO')
                try:
                    self.collect_reports()
                except Exception as error:
                    self.summary['report_collection_error'] = str(error)
                    self.summary['status'] = 'failed'
                    code = 1
                self.summary['finished_at'] = datetime.now(timezone.utc).isoformat()
                self.summary['duration_seconds'] = round(time.monotonic() - self.started, 2)
                self.summary['sha256'] = {}
                for apk in self.output.glob('*.apk'):
                    digest = hashlib.sha256()
                    with apk.open('rb') as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                            digest.update(chunk)
                    self.summary['sha256'][apk.name] = digest.hexdigest()
                (self.output / 'summary.json').write_text(json.dumps(self.summary, indent=2, ensure_ascii=False), encoding='utf-8')
        self.show_result()
        return code


def main():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8', errors='replace')
    args = parser().parse_args()
    if args.init_config:
        if args.no_config:
            parser().error('--init-config cannot be used with --no-config')
        try:
            init_config(args.config)
        except OSError:
            print('Cannot create configuration: file already exists or path is not writable.', file=sys.stderr)
            return 1
        return 0
    if args.console_log:
        args.console_log.parent.mkdir(parents=True, exist_ok=True)
        # Detached Windows processes must not depend on a parent's console handles.
        sys.stdout = sys.stderr = args.console_log.open('w', encoding='utf-8', buffering=1)
    build = Build(args)
    if args.dry_run:
        try:
            build.validate_options()
        except RuntimeError as error:
            build.console.line(str(error), 'FAIL')
            return 1
        build.banner(preview=True)
        stages = ['preflight', *dict.fromkeys(args.targets)]
        if args.sign:
            stages.append('sign-android')
        if args.notify_telegram:
            stages.append('telegram')
        for index, name in enumerate(stages, 1):
            build.console.line(f'[{index}/{len(stages)}] {STAGES[name]}', 'INFO')
        build.console.line('计划预览完成，未执行构建。', 'INFO')
        return 0
    code = 1
    try:
        code = build.execute()
    except Exception as error:
        build.console.line(str(error), 'FAIL')
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

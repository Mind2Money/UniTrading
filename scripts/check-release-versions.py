"""Check that every platform identifies the same release before expensive builds."""
import os
from pathlib import Path
import plistlib
import re
import sys

ROOT = Path(__file__).resolve().parent.parent


def match_one(pattern, text, label):
    matches = re.findall(pattern, text, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError(f"Expected one {label}, found {len(matches)}")
    return matches[0]


def validate(root=ROOT, tag=None):
    android = (root / 'androidApp/build.gradle.kts').read_text(encoding='utf-8')
    version = match_one(r'^\s*versionName\s*=\s*"([^"]+)"', android, 'Android versionName')
    code = match_one(r'^\s*versionCode\s*=\s*(\d+)', android, 'Android versionCode')
    desktop = (root / 'composeApp/build.gradle.kts').read_text(encoding='utf-8')
    rithmic = (root / 'rithmic-broker/src/commonMain/kotlin/com/unitrading/rithmic/RithmicSession.kt').read_text(encoding='utf-8')
    versions = {
        'desktop': match_one(r'^\s*packageVersion\s*=\s*"([^"]+)"', desktop, 'desktop packageVersion'),
        'Rithmic': match_one(r'^\s*const val DEFAULT_APP_VERSION\s*=\s*"([^"]+)"', rithmic, 'Rithmic version'),
    }
    for label, path in [('iOS app', 'iosApp/iosApp/Info.plist'), ('iOS widget', 'iosApp/UniTradingWidgets/Info.plist')]:
        with (root / path).open('rb') as f:
            info = plistlib.load(f)
        versions[label] = info.get('CFBundleShortVersionString')
        if str(info.get('CFBundleVersion')) != code:
            raise ValueError(f"{label} build number {info.get('CFBundleVersion')} does not match Android versionCode {code}")
    for label, value in versions.items():
        if value != version:
            raise ValueError(f"{label} version {value} does not match Android versionName {version}")
    if tag and tag != 'v' + version:
        raise ValueError(f"Tag {tag} does not match version {version}")
    return version


if __name__ == '__main__':
    try:
        tag = os.getenv('GITHUB_REF_NAME') if os.getenv('GITHUB_REF_TYPE') == 'tag' else None
        print(f"Release versions match: {validate(tag=tag)}")
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        print(f"Release version check failed: {error}", file=sys.stderr)
        sys.exit(1)

#!/usr/bin/env bash
# Run from the repository root on an Apple Silicon Mac.
set -euo pipefail
artifacts="${IOS_ARTIFACT_DIR:?IOS_ARTIFACT_DIR is required}"
mkdir -p "$artifacts"
device=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(x["udid"] for k,v in d["devices"].items() if "iOS" in k for x in v if "iPhone" in x["name"]))')
xcrun simctl boot "$device" || true
xcrun simctl bootstatus "$device" -b
# Simulator-only Keychain group; never use it for device distribution. Xcode
# links simulator entitlements into the executable's __entitlements section and
# signs ad hoc without them. Restricted entitlements inside an ad hoc signature
# make the simulator refuse to launch the app.
entitlements="$artifacts/ios-ci.entitlements"
python3 - "$entitlements" <<'PY'
import plistlib, sys
from pathlib import Path
Path(sys.argv[1]).write_bytes(plistlib.dumps({'keychain-access-groups': ['CI.com.unitrading.demo']}))
PY
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug -destination "id=$device" -derivedDataPath "$artifacts/ios-build" ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- CODE_SIGN_ENTITLEMENTS="$entitlements" build
app="$artifacts/ios-build/Build/Products/Debug-iphonesimulator/UniTrading.app"
if ! otool -l "$app/UniTrading" | grep 'sectname __entitlements'; then
  echo "::error::Simulator entitlements were not linked into the app executable"
  exit 1
fi
codesign --verify --deep --strict "$app"
xcrun simctl install "$device" "$app"
python3 - "$device" "$artifacts" <<'PY'
import subprocess, sys
from pathlib import Path
for flag, marker, filename in [('--native-platform-self-test', 'NATIVE_PLATFORM_SELF_TEST PASS', 'ios-platform-test.log'), ('--native-ui-self-test', 'NATIVE_UI_SELF_TEST PASS', 'ios-ui-test.log')]:
    try:
        result = subprocess.run(['xcrun', 'simctl', 'launch', '--console', sys.argv[1], 'com.unitrading.demo', flag], text=True, capture_output=True, timeout=90)
    except subprocess.TimeoutExpired as error:
        Path(sys.argv[2], filename).write_text(f'{flag} timed out; check DEBUG build configuration.')
        subprocess.run(['xcrun', 'simctl', 'terminate', sys.argv[1], 'com.unitrading.demo'], check=False)
        raise SystemExit(str(error))
    log = result.stdout + result.stderr
    Path(sys.argv[2], filename).write_text(log)
    print(log)
    if result.returncode != 0 or marker not in log:
        raise SystemExit(f'Native self-test did not pass: {flag}')
PY
grep -q 'NATIVE_PLATFORM_SELF_TEST PASS' "$artifacts/ios-platform-test.log"
grep -q 'NATIVE_UI_SELF_TEST PASS' "$artifacts/ios-ui-test.log"

#!/usr/bin/env bash
# Builds the macOS login host (Apple Silicon) and installs it only after its offline self-test passes.
set -euo pipefail

source_dir="$(cd "$(dirname "$0")" && pwd)"
output="${1:-$source_dir/build/1.1.1}"
name=UniTrading.TradovateLogin
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# A bare executable has no bundle; this embedded plist gives WebKit an identifier to keep the site data under.
cat > "$work/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>com.unitrading.tradovate-login</string>
  <key>CFBundleName</key><string>UniTrading Login</string>
</dict>
</plist>
PLIST

swiftc -O -swift-version 5 -target arm64-apple-macos13.0 -framework AppKit -framework WebKit \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$work/Info.plist" \
  "$source_dir/TradovateLogin.swift" -o "$work/$name"

result="$("$work/$name" --self-test)"
case "$result" in
  "SELF_TEST PASS "*) echo "$result" ;;
  *) echo 'Native login host self-test failed.' >&2; exit 1 ;;
esac

mkdir -p "$output"
cp "$work/$name" "$output/$name"
echo "Native login host built: $output/$name"

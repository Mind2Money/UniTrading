"""Require fresh, nonempty Android test evidence; Gradle success alone is insufficient."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--since-file", type=Path)
args = parser.parse_args()
minimum_time = args.since_file.stat().st_mtime if args.since_file else 0
files = list(args.directory.rglob("TEST-*.xml"))
if not files:
    raise SystemExit(f"No Android JUnit reports in {args.directory}")
classes = set()
total = 0
for file in files:
    if file.stat().st_mtime < minimum_time:
        raise SystemExit(f"Stale Android test report: {file}")
    root = ET.parse(file).getroot()
    for suite in root.iter("testsuite"):
        if any(int(suite.get(name, "0")) for name in ("failures", "errors", "skipped")):
            raise SystemExit(f"Android suite reported failures, errors, or skipped tests: {file}")
    for case in root.iter("testcase"):
        if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
            raise SystemExit(f"Android test did not pass: {case.get('classname')}.{case.get('name')}")
        total += 1
        classes.add(case.get("classname", ""))
required = {"com.unitrading.android.AndroidCredentialStoreTest", "com.unitrading.android.AppearanceUiTest"}
if total < 3 or not required.issubset(classes):
    raise SystemExit(f"Required device tests did not execute: count={total}, classes={sorted(classes)}")
print(f"Verified {total} Android device tests from {len(files)} fresh reports")

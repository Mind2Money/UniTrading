"""Regression checks for cross-platform release versions."""
import importlib.util
from pathlib import Path
import plistlib
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_versions', Path(__file__).with_name('check-release-versions.py'))
release_versions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_versions)


class ReleaseVersionsTest(unittest.TestCase):
    def test_current_versions_match(self):
        version = release_versions.validate()
        self.assertEqual(version, release_versions.validate(tag='v' + version))
        with self.assertRaisesRegex(ValueError, 'Tag'):
            release_versions.validate(tag='v0.0.0')

    def test_outdated_ios_widget_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for path in ('androidApp/build.gradle.kts', 'composeApp/build.gradle.kts',
                         'rithmic-broker/src/commonMain/kotlin/com/unitrading/rithmic/RithmicSession.kt',
                         'iosApp/iosApp/Info.plist', 'iosApp/UniTradingWidgets/Info.plist'):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(release_versions.ROOT / path, target)
            widget = root / 'iosApp/UniTradingWidgets/Info.plist'
            with widget.open('rb') as f:
                info = plistlib.load(f)
            info['CFBundleShortVersionString'] = '0.0.0'
            with widget.open('wb') as f:
                plistlib.dump(info, f)
            with self.assertRaisesRegex(ValueError, 'iOS widget version'):
                release_versions.validate(root)


if __name__ == '__main__':
    unittest.main()

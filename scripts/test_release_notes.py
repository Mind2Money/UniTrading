"""Checks for the release notes the tag workflow publishes."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_notes', Path(__file__).with_name('release-notes.py'))
release_notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_notes)

NOTES = '''# Unreleased

## 简体中文

later

# v1.1.0

## 简体中文

中文说明

## English

English notes

# v1.0.0

## English

older
'''


class AnnouncementTest(unittest.TestCase):
    def test_returns_only_the_tag_section(self):
        self.assertEqual(release_notes.announcement(NOTES, 'v1.1.0'),
                         '## 简体中文\n\n中文说明\n\n## English\n\nEnglish notes')

    def test_reads_crlf_files(self):
        self.assertIn('中文说明', release_notes.announcement(NOTES.replace('\n', '\r\n'), 'v1.1.0'))

    def test_rejects_a_tag_without_a_section(self):
        with self.assertRaisesRegex(ValueError, 'v9.9.9'):
            release_notes.announcement(NOTES, 'v9.9.9')

    def test_rejects_a_section_missing_a_language(self):
        with self.assertRaisesRegex(ValueError, '简体中文'):
            release_notes.announcement(NOTES, 'v1.0.0')


class CommitsTest(unittest.TestCase):
    def test_lists_commits_since_the_previous_tag_and_everything_for_the_first(self):
        with tempfile.TemporaryDirectory() as folder:
            def git(*args):
                subprocess.run(['git', '-c', 'user.name=t', '-c', 'user.email=t@example.com', *args],
                               cwd=folder, check=True, capture_output=True)
            git('init', '-q')
            for minor, name in enumerate(('first', 'second')):
                git('commit', '-q', '--allow-empty', '-m', name)
                git('tag', '-a', f'v1.{minor}.0', '-m', name)
            self.assertIn('- first', release_notes.commits('v1.0.0', folder))
            listed = release_notes.commits('v1.1.0', folder)
            self.assertIn('- second', listed)
            self.assertNotIn('first', listed)


if __name__ == '__main__':
    unittest.main()

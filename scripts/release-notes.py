#!/usr/bin/env python3
"""Release notes for a tag: its `# vX.Y.Z` section of docs/release-notes.md.

A section needs both languages, so a tag without an announcement fails the release instead
of publishing an empty one. --commits adds one line per commit since the previous v* tag.

Usage: python3 scripts/release-notes.py v1.2.3 [--commits]
"""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
LANGUAGES = ('## 简体中文', '## English')


def announcement(text, tag):
    """The text under `# <tag>`, up to the next top-level heading."""
    lines = [line.rstrip() for line in text.replace('\r\n', '\n').split('\n')]
    heading = f'# {tag}'
    if heading not in lines:
        raise ValueError(f'Add a {heading} section to docs/release-notes.md before publishing')
    body = []
    for line in lines[lines.index(heading) + 1:]:
        if line.startswith('# '):
            break
        body.append(line)
    missing = [language for language in LANGUAGES if language not in body]
    if missing:
        raise ValueError(f'{heading} is missing {", ".join(missing)}')
    return '\n'.join(body).strip()


def commits(tag, cwd=ROOT):
    """One line per commit since the previous v* tag; the first release lists everything."""
    def git(*args):
        return subprocess.run(['git', *args], cwd=cwd, check=True, capture_output=True,
                              encoding='utf-8').stdout.strip()
    try:
        previous = git('describe', '--tags', '--abbrev=0', '--match', 'v[0-9]*', f'{tag}^')
    except subprocess.CalledProcessError:
        previous = None
    return git('log', '--no-merges', '--pretty=format:- %s (%h)', f'{previous}..{tag}' if previous else tag)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('tag')
    parser.add_argument('--commits', action='store_true')
    args = parser.parse_args()
    try:
        notes = announcement((ROOT / 'docs/release-notes.md').read_text(encoding='utf-8'), args.tag)
    except ValueError as error:
        parser.exit(1, f'{error}\n')
    if args.commits:
        notes += f'\n\n## Commits\n\n{commits(args.tag)}'
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    print(notes)


if __name__ == '__main__':
    main()

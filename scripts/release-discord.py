#!/usr/bin/env python3
"""Announce a release on Discord with the Chinese block of its docs/release-notes.md section.

This repository is public, so anything attached to a Release is public too; the send is recorded
as a commit status on the tagged commit instead. `pending` goes up before the request, so a lost
reply cannot turn a rerun into a second post: it stops the script until someone has checked the
channel (docs/local-build.md). `failure` means Discord refused the message, so a rerun sends.

Usage: python3 scripts/release-discord.py v1.2.3 [--dry-run]
Sending needs DISCORD_RELEASE_WEBHOOK and a gh login that can write commit statuses.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = 'Mind2Money/UniTrading'
# Cloudflare refuses urllib's default agent in front of Discord.
USER_AGENT = f'UniTrading-release (https://github.com/{REPOSITORY})'
TAG = re.compile(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)')
MESSAGE_ID = re.compile(r'\d{5,25}')
WEBHOOK = re.compile(r'https://discord\.com/api(/v10)?/webhooks/\d{5,25}/[A-Za-z0-9_-]+')
# Discord's limits for one embed description and for all embeds of a message, counted in UTF-16 units (an emoji
# counts two). The preview leaves room for its notice.
DESCRIPTION_LIMIT = 4096
MESSAGE_LIMIT = 6000
PREVIEW_LIMIT = 5700
# Discord also caps the whole message in UTF-8 bytes and publishes no number: it refused v1.5.0 with HTTP 413,
# "Message size exceeds maximum allowed. Oversized fields: message.embeds=10602, message.content=97", after
# v1.4.0's 7,382 bytes had gone through. A message stays within the size known to work.
MESSAGE_BYTES = 7000
NOTICE = '\n\n📎 完整更新日志见附件。'

spec = importlib.util.spec_from_file_location('release_notes', Path(__file__).with_name('release-notes.py'))
release_notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_notes)


class ReleaseError(Exception):
    """Stops the announcement; the message never holds the webhook."""


class DiscordRefused(ReleaseError):
    """Discord answered without keeping the message, so a rerun may send it."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def units(text):
    return len(text.encode('utf-16-le')) // 2


def prefix(text, length):
    """The start of `text` within `length` UTF-16 units; an emoji that does not fit is dropped whole."""
    return text.encode('utf-16-le')[:length * 2].decode('utf-16-le', 'ignore')


def fit(text, max_units, max_bytes):
    """The start of `text` within `max_units` UTF-16 units and `max_bytes` UTF-8 bytes. A cut text ends before the
    last blank line in its second half, so no section loses its end or keeps only its title, or else before the
    last line break there; a character that does not fit is dropped whole."""
    part = prefix(text, max_units)
    part = part.encode('utf-8')[:max_bytes].decode('utf-8', 'ignore')
    if len(part) < len(text):
        for separator in ('\n\n', '\n'):
            cut = part.rfind(separator)
            if cut > len(part) / 2:
                return part[:cut]
    return part


def chinese_block(section):
    """The `## 简体中文` block of a section that release_notes.announcement() returned."""
    lines = section.split('\n')
    start = lines.index(release_notes.LANGUAGES[0]) + 1
    end = next((index for index in range(start, len(lines)) if lines[index] in release_notes.LANGUAGES), len(lines))
    return '\n'.join(lines[start:end]).strip()


def build_announcement(tag, notes):
    """The webhook payload, plus the complete notes as a file when they exceed one message."""
    if not TAG.fullmatch(tag):
        raise ValueError('Expected a release tag vX.Y.Z')
    if not notes:
        raise ValueError(f'The Chinese announcement of {tag} is empty')
    link = f'https://github.com/{REPOSITORY}/releases/tag/{tag}'
    content = f'🎉 UniTrading {tag}\n📥 下载：{link}'
    room = MESSAGE_BYTES - len(content.encode('utf-8'))
    attachment = None
    remaining = notes
    if units(notes) > MESSAGE_LIMIT or len(notes.encode('utf-8')) > room:
        attachment = {'name': f'UniTrading-{tag}-release-notes.md', 'text': notes + '\n'}
        remaining = fit(notes, PREVIEW_LIMIT, room - len(NOTICE.encode('utf-8'))) + NOTICE
    embeds = []
    while remaining:
        part = prefix(remaining, DESCRIPTION_LIMIT)
        newline = part.rfind('\n')
        if len(part) < len(remaining) and newline > len(part) / 2:
            part = part[:newline + 1]
        embeds.append({'description': part})
        remaining = remaining[len(part):]
    payload = {'content': content, 'embeds': embeds, 'allowed_mentions': {'parse': []}}
    return payload, attachment


def request_body(payload, attachment):
    """JSON, or multipart/form-data when the complete notes go along as a file. UTF-8 rather than \\u escapes,
    which take six bytes for each Chinese character."""
    text = json.dumps(payload, ensure_ascii=False, separators=(',', ':'))
    if attachment is None:
        return text.encode('utf-8'), 'application/json'
    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="payload_json"\r\n'
            f'Content-Type: application/json\r\n\r\n{text}\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="files[0]"; filename="{attachment["name"]}"\r\n'
            f'Content-Type: text/markdown; charset=utf-8\r\n\r\n{attachment["text"]}\r\n'
            f'--{boundary}--\r\n')
    return body.encode(), f'multipart/form-data; boundary={boundary}'


def refusal_reason(error):
    """Discord's own message and code from an error reply, such as ' (Request entity too large, code 40005)'."""
    try:
        reply = json.loads(error.read(2000) or b'{}')
    except Exception:
        reply = {}
    finally:
        error.close()
    if not isinstance(reply, dict):
        return ''
    parts = [str(reply[key])[:200] for key in ('message', 'code') if reply.get(key) not in (None, '')]
    if len(parts) == 2:
        parts[1] = f'code {parts[1]}'
    return f" ({', '.join(parts)})" if parts else ''


def post(url, payload, attachment):
    """Send the message and return its ID. Failures carry no part of the URL."""
    body, content_type = request_body(payload, attachment)
    request = urllib.request.Request(url, body, {'Content-Type': content_type, 'User-Agent': USER_AGENT}, method='POST')
    unknown = 'inspect the channel before retrying (the commit status stays pending)'
    try:
        with OPENER.open(request, timeout=15) as response:
            message_id = json.load(response).get('id')
    except urllib.error.HTTPError as error:
        reason = refusal_reason(error)
        # A 5xx can follow a saved message; a refused redirect or a 4xx cannot.
        if error.code < 500:
            raise DiscordRefused(f'Discord returned HTTP {error.code}{reason}; nothing was posted') from None
        raise ReleaseError(f'Discord returned HTTP {error.code}; {unknown}') from None
    except Exception:
        raise ReleaseError(f'Discord send outcome is unknown; {unknown}') from None
    if not MESSAGE_ID.fullmatch(str(message_id)):
        raise ReleaseError(f'Discord did not confirm a message ID; {unknown}')
    return message_id


def github(*args):
    completed = subprocess.run(['gh', 'api', *args], capture_output=True, encoding='utf-8', timeout=30)
    if completed.returncode != 0:
        raise ReleaseError(f'gh api failed: {completed.stderr.strip()}')
    return completed.stdout


def tag_commit(tag):
    return subprocess.run(['git', 'rev-parse', f'{tag}^{{commit}}'], cwd=ROOT, check=True, capture_output=True,
                          encoding='utf-8').stdout.strip()


def announce(tag, commit, payload, attachment, webhook, send=post, api=github, log=print):
    """Post the message once per tagged commit, whatever reruns follow."""
    if not WEBHOOK.fullmatch(webhook or ''):
        raise ReleaseError('Set DISCORD_RELEASE_WEBHOOK to a Discord webhook URL without query parameters')
    context = f'discord-announcement/{tag}'

    def record(state, description):
        api('--method', 'POST', f'repos/{REPOSITORY}/statuses/{commit}', '-f', f'state={state}',
            '-f', f'context={context}', '-f', f'description={description}')

    statuses = json.loads(api(f'repos/{REPOSITORY}/commits/{commit}/status'))['statuses']
    state = next((status['state'] for status in statuses if status['context'] == context), None)
    if state == 'success':
        log(f'Discord announcement for {tag} was already sent; skipped')
        return
    if state == 'pending':
        raise ReleaseError(f'The Discord announcement for {tag} may already be posted; inspect the channel '
                           'and resolve the commit status as docs/local-build.md describes')
    record('pending', 'Send started; Discord has not confirmed it')
    try:
        message_id = send(f'{webhook}?wait=true', payload, attachment)
    except DiscordRefused:
        record('failure', 'Discord refused the message; nothing was posted')
        raise
    try:
        record('success', f'Posted as message {message_id}')
    except Exception:
        raise ReleaseError(f'Discord posted message {message_id}, but recording it failed; do not resend') from None
    log(f'Discord announcement sent for {tag} ({message_id})')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('tag')
    parser.add_argument('--dry-run', action='store_true', help='print the message without sending it')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    try:
        section = release_notes.announcement((ROOT / 'docs/release-notes.md').read_text(encoding='utf-8'), args.tag)
        payload, attachment = build_announcement(args.tag, chinese_block(section))
        if args.dry_run:
            print(json.dumps({'payload': payload, 'attachment': attachment}, ensure_ascii=False, indent=2))
            return
        announce(args.tag, tag_commit(args.tag), payload, attachment, os.environ.get('DISCORD_RELEASE_WEBHOOK'))
    except (ValueError, ReleaseError) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()

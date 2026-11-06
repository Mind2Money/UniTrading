"""Checks for the release announcement the tag workflow sends to Discord."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import threading
import unittest

spec = importlib.util.spec_from_file_location('release_discord', Path(__file__).with_name('release-discord.py'))
release_discord = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_discord)

TAG = 'v1.2.3'
COMMIT = 'a' * 40
MESSAGE_ID = '123456789012345678'
WEBHOOK = 'https://discord.com/api/webhooks/123456789012345678/test-secret-token'
PAYLOAD = {'content': 'hello'}
CONTEXT = f'discord-announcement/{TAG}'


class ChineseBlockTest(unittest.TestCase):
    def test_selects_chinese_in_either_language_order(self):
        chinese, english = '## 简体中文\n\n中文公告', '## English\n\nEnglish notes'
        for section in (f'{chinese}\n\n{english}', f'{english}\n\n{chinese}'):
            self.assertEqual(release_discord.chinese_block(section), '中文公告')


class BuildAnnouncementTest(unittest.TestCase):
    def test_links_the_release_and_disables_mentions(self):
        payload, attachment = release_discord.build_announcement(TAG, '✅ 修复')
        self.assertEqual(payload['content'],
                         f'🎉 UniTrading {TAG}\n📥 下载：https://github.com/Mind2Money/UniTrading/releases/tag/{TAG}')
        self.assertEqual(payload['embeds'], [{'description': '✅ 修复'}])
        self.assertEqual(payload['allowed_mentions'], {'parse': []})
        self.assertIsNone(attachment)

    def test_stays_within_discord_limits_and_keeps_emoji_whole(self):
        for notes in ('a' * 4095 + '🎉' + 'b' * 1903, '中\n' * 1200, 'a' * 5699 + '🎉' + 'end' * 500):
            payload, attachment = release_discord.build_announcement(TAG, notes)
            parts = [embed['description'] for embed in payload['embeds']]
            joined = ''.join(parts)
            self.assertTrue(all(release_discord.units(part) <= 4096 for part in parts))
            self.assertLessEqual(release_discord.units(joined), 6000)
            joined.encode('utf-8')  # A split surrogate pair cannot be encoded.
            if release_discord.units(notes) <= 6000:
                self.assertEqual(joined, notes)
                self.assertIsNone(attachment)
            else:
                self.assertTrue(joined.endswith('📎 完整更新日志见附件。'))
                self.assertEqual(attachment, {'name': f'UniTrading-{TAG}-release-notes.md', 'text': notes + '\n'})

    def test_rejects_a_bad_tag_or_empty_notes(self):
        with self.assertRaisesRegex(ValueError, 'vX.Y.Z'):
            release_discord.build_announcement('v01.2.3', '说明')
        with self.assertRaisesRegex(ValueError, 'empty'):
            release_discord.build_announcement(TAG, '')


class FakeGitHub:
    """The latest commit status per context, as the combined status endpoint returns it."""

    def __init__(self, state=None):
        self.states = [state] if state else []
        self.calls = []
        self.fail_on = None

    def __call__(self, *args):
        self.calls.append(args)
        if args[0] != '--method':
            latest = [{'context': CONTEXT, 'state': self.states[-1]}] if self.states else []
            return json.dumps({'statuses': [{'context': 'other', 'state': 'success'}, *latest]})
        fields = dict(arg.split('=', 1) for arg in args if '=' in arg)
        self.last_write = (args[2], fields['context'])
        if fields['state'] == self.fail_on:
            raise release_discord.ReleaseError('gh api failed')
        self.states.append(fields['state'])
        return ''


class AnnounceTest(unittest.TestCase):
    def send(self, api, sender):
        logs = []
        release_discord.announce(TAG, COMMIT, PAYLOAD, None, WEBHOOK, send=sender, api=api, log=logs.append)
        return logs

    def test_reserves_before_sending_records_the_message_and_skips_reruns(self):
        api = FakeGitHub()
        sent = []

        def sender(url, payload, attachment):
            self.assertEqual(api.states, ['pending'])
            sent.append(url)
            return MESSAGE_ID

        self.assertIn(MESSAGE_ID, self.send(api, sender)[0])
        self.assertEqual(api.states, ['pending', 'success'])
        self.assertEqual(api.last_write, (f'repos/Mind2Money/UniTrading/statuses/{COMMIT}', CONTEXT))
        self.assertIn('already sent', self.send(api, sender)[0])
        self.assertEqual(sent, [f'{WEBHOOK}?wait=true'])
        self.assertNotIn('test-secret-token', repr(api.calls))

    def test_pending_status_blocks_a_blind_resend(self):
        api = FakeGitHub('pending')
        with self.assertRaisesRegex(release_discord.ReleaseError, 'may already be posted'):
            self.send(api, lambda *_: self.fail('sent while pending'))
        self.assertEqual(api.states, ['pending'])

    def test_refused_message_can_be_sent_by_a_rerun(self):
        api = FakeGitHub()

        def refused(*_):
            raise release_discord.DiscordRefused('Discord returned HTTP 404; nothing was posted')

        with self.assertRaisesRegex(release_discord.DiscordRefused, '404'):
            self.send(api, refused)
        self.assertEqual(api.states, ['pending', 'failure'])
        self.send(api, lambda *_: MESSAGE_ID)
        self.assertEqual(api.states[-1], 'success')

    def test_uncertain_outcome_stays_pending(self):
        api = FakeGitHub()

        def uncertain(*_):
            raise release_discord.ReleaseError('Discord send outcome is unknown')

        with self.assertRaisesRegex(release_discord.ReleaseError, 'unknown'):
            self.send(api, uncertain)
        self.assertEqual(api.states, ['pending'])

    def test_lost_success_status_warns_against_resending(self):
        api = FakeGitHub()
        api.fail_on = 'success'
        with self.assertRaisesRegex(release_discord.ReleaseError, f'{MESSAGE_ID}.*do not resend'):
            self.send(api, lambda *_: MESSAGE_ID)
        self.assertEqual(api.states, ['pending'])

    def test_rejects_other_urls_without_naming_them_or_touching_github(self):
        for webhook in (None, '', 'http://discord.com/api/webhooks/123456/secret-part',
                        'https://discord.com.evil.test/api/webhooks/123456/secret-part',
                        'https://user:secret-part@discord.com/api/webhooks/123456/secret-part',
                        f'{WEBHOOK}?thread_id=1'):
            api = FakeGitHub()
            with self.assertRaises(release_discord.ReleaseError) as raised:
                release_discord.announce(TAG, COMMIT, PAYLOAD, None, webhook, send=None, api=api)
            self.assertNotIn('secret-part', str(raised.exception))
            self.assertEqual(api.calls, [])


class FakeDiscord(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers['Content-Length'])
        self.server.received.append((self.path, dict(self.headers), self.rfile.read(length)))
        status, headers, body = self.server.reply
        self.send_response(status)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class PostTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), FakeDiscord)
        self.server.received = []
        self.server.reply = (200, {}, json.dumps({'id': MESSAGE_ID}).encode())
        self.url = f'http://127.0.0.1:{self.server.server_port}/send?wait=true'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def test_sends_json_with_a_named_agent_and_returns_the_message_id(self):
        self.assertEqual(release_discord.post(self.url, PAYLOAD, None), MESSAGE_ID)
        _, headers, body = self.server.received[0]
        self.assertEqual(json.loads(body), PAYLOAD)
        self.assertEqual(headers['Content-Type'], 'application/json')
        self.assertIn('UniTrading', headers['User-Agent'])

    def test_sends_the_complete_notes_as_a_file(self):
        attachment = {'name': 'notes.md', 'text': '完整说明\n'}
        release_discord.post(self.url, PAYLOAD, attachment)
        _, headers, body = self.server.received[0]
        self.assertIn('multipart/form-data', headers['Content-Type'])
        text = body.decode()
        self.assertIn('name="payload_json"', text)
        self.assertIn('name="files[0]"; filename="notes.md"', text)
        self.assertIn('完整说明\n', text)

    def test_does_not_follow_a_redirect(self):
        self.server.reply = (302, {'Location': f'{self.url}&moved'}, b'')
        with self.assertRaisesRegex(release_discord.DiscordRefused, '302'):
            release_discord.post(self.url, PAYLOAD, None)
        self.assertEqual(len(self.server.received), 1)

    def test_a_4xx_is_a_refusal_and_a_5xx_is_uncertain(self):
        self.server.reply = (404, {}, b'{}')
        with self.assertRaisesRegex(release_discord.DiscordRefused, '404'):
            release_discord.post(self.url, PAYLOAD, None)
        self.server.reply = (500, {}, b'{}')
        with self.assertRaisesRegex(release_discord.ReleaseError, '500') as raised:
            release_discord.post(self.url, PAYLOAD, None)
        self.assertNotIsInstance(raised.exception, release_discord.DiscordRefused)

    def test_an_unreadable_reply_is_uncertain_and_names_no_url(self):
        self.server.reply = (200, {}, b'not json')
        with self.assertRaises(release_discord.ReleaseError) as raised:
            release_discord.post(self.url, PAYLOAD, None)
        self.assertNotIsInstance(raised.exception, release_discord.DiscordRefused)
        self.assertNotIn('127.0.0.1', str(raised.exception))
        self.assertIsNone(raised.exception.__cause__)


if __name__ == '__main__':
    unittest.main()

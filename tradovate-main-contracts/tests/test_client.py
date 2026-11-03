"""Protocol tests use fake sockets and metadata; no account credentials/network."""

import io
import json
import unittest
from collections import deque
from unittest.mock import patch
from urllib.error import HTTPError

from main_contracts.client import HEARTBEAT_SECONDS, MARKET_HOSTS, TradovateClient
from main_contracts.core import CompletedSession, ResolutionError


TOKEN = "test-token-credentials-should-never-appear"
SESSION = CompletedSession(20261102, 1, 2, 9_999_999_999_999, False)
TAIL_SESSION = CompletedSession(20261102, 1, 2, 9_999_999_999_999, True)


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now


class FakeSocket:
    def __init__(self, clock, chart_handler=None):
        self.clock = clock
        self.chart_handler = chart_handler or self.normal_chart
        self.messages = deque(["o"])
        self.sent = []
        self.timeout = 0
        self.closed = False
        self.cancel_handler = None
        self.authorize_error = False

    def settimeout(self, timeout):
        self.timeout = timeout

    def recv(self):
        if not self.messages or self.messages[0] is None:
            if self.messages:
                self.messages.popleft()
            self.clock.now += self.timeout
            raise TimeoutError()
        return self.messages.popleft()

    def ack(self, request_id, data=None, status=200):
        self.messages.append("a" + json.dumps([{"i": request_id, "s": status, "d": data or {}}]))

    def chart(self, channel=101, volume=42, eoh=True, bars=None):
        if bars is None:
            bars = [{"timestamp": "2026-11-02T00:00:00Z", "upVolume": volume, "downVolume": 0}]
        self.messages.append("a" + json.dumps([{"e": "chart", "d": {"charts": [
            {"id": channel, "bars": bars, "eoh": eoh},
        ]}}]))

    def normal_chart(self, request_id, body):
        self.chart()  # Deliberately before the subscription ACK.
        self.ack(request_id, {"historicalId": 101, "realtimeId": 101, "mode": "RealTime"})

    def send(self, text):
        self.sent.append(text)
        if text == "[]":
            return
        route, request_id, _, raw = text.split("\n", 3)
        request_id = int(request_id)
        if route == "authorize":
            self.ack(request_id, {"errorText": TOKEN} if self.authorize_error else {})
        elif route == "md/getChart":
            self.chart_handler(request_id, json.loads(raw))
        elif route == "md/cancelChart":
            if self.cancel_handler:
                self.cancel_handler(request_id, json.loads(raw))
            else:
                self.ack(request_id)

    def close(self, timeout=None):
        self.closed = True

    def requests(self, route):
        return [json.loads(message.split("\n\n", 1)[1]) for message in self.sent if message.startswith(route + "\n")]


class Response:
    status = 200

    def __init__(self, data):
        self.raw = data if isinstance(data, bytes) else json.dumps(data).encode()

    def read(self, size):
        return self.raw[:size]

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.socket = FakeSocket(self.clock)
        self.clock_patch = patch("main_contracts.client.time.monotonic", self.clock.monotonic)
        self.clock_patch.start()
        self.factory_patch = patch("main_contracts.client._connect_socket", return_value=self.socket)
        self.factory = self.factory_patch.start()
        self.client = TradovateClient(TOKEN, timeout=8)

    def tearDown(self):
        self.client.close()
        self.factory_patch.stop()
        self.clock_patch.stop()

    def assert_code(self, code, function):
        with self.assertRaises(ResolutionError) as caught:
            function()
        self.assertEqual(code, caught.exception.code)
        self.assertNotIn(TOKEN, str(caught.exception))

    def test_early_history_shared_channel_and_precise_day_range(self):
        self.assertEqual(42, self.client.daily_volume("MESZ6", SESSION))
        self.assertEqual([{"subscriptionId": 101}], self.socket.requests("md/cancelChart"))
        body = self.socket.requests("md/getChart")[0]
        self.assertEqual("DailyBar", body["chartDescription"]["underlyingType"])
        self.assertEqual("2026-11-02T00:00:00Z", body["timeRange"]["asFarAsTimestamp"])
        self.assertEqual("2026-11-02T23:59:59.999Z", body["timeRange"]["closestTimestamp"])
        self.assertNotIn("asMuchAsElements", body["timeRange"])

    def test_dated_symbol_accepts_literal_server_root_punctuation(self):
        self.assertEqual(42, self.client.daily_volume("TEST.ROOTZ6", SESSION))
        self.assertEqual("TEST.ROOTZ6", self.socket.requests("md/getChart")[0]["symbol"])

    def test_fractional_and_text_channels_are_cancelled_with_original_types(self):
        def chart(request_id, body):
            self.socket.chart("history", volume=7)
            self.socket.ack(request_id, {"historicalId": "history", "realtimeId": 12.5, "mode": "RealTime"})
        self.socket.chart_handler = chart
        self.assertEqual(7, self.client.daily_volume("GCZ6", TAIL_SESSION))
        self.assertEqual([{"subscriptionId": "history"}, {"subscriptionId": 12.5}], self.socket.requests("md/cancelChart"))

    def test_fractional_channel_ids_retain_lexical_precision(self):
        exact = "123456789.123456789123456789"
        def chart(request_id, body):
            self.socket.messages.append('a[{"e":"chart","d":{"charts":[{"id":' + exact + ',"bars":[{"timestamp":"2026-11-02T00:00:00Z","upVolume":42,"downVolume":0}],"eoh":true}]}}]')
            self.socket.messages.append('a[{"i":' + str(request_id) + ',"s":200,"d":{"historicalId":' + exact + ',"realtimeId":' + exact + ',"mode":"RealTime"}}]')
        self.socket.chart_handler = chart
        self.assertEqual(42, self.client.daily_volume("MESZ6", SESSION))
        cancellations = [message for message in self.socket.sent if message.startswith("md/cancelChart\n")]
        self.assertEqual(1, len(cancellations))
        self.assertIn('"subscriptionId":' + exact, cancellations[0])

    def test_tail_requires_real_time_mode_and_channel(self):
        for response in ({"historicalId": 101}, {"historicalId": 101, "mode": "RealTime"}):
            with self.subTest(response=response):
                self.socket.chart_handler = lambda request_id, body: self.socket.ack(request_id, response)
                self.assert_code("MARKET_DATA_DENIED", lambda: self.client.daily_volume("MESZ6", TAIL_SESSION))

    def test_explicit_ended_empty_realtime_history_counts_zero(self):
        def chart(request_id, body):
            self.socket.chart(bars=[])
            self.socket.ack(request_id, {"historicalId": 101, "realtimeId": 101, "mode": "RealTime"})
        self.socket.chart_handler = chart
        self.assertEqual(0, self.client.daily_volume("MESZ6", SESSION))

    def test_ended_empty_without_realtime_mode_is_not_zero(self):
        def chart(request_id, body):
            self.socket.chart(bars=[])
            self.socket.ack(request_id, {"historicalId": 101})
        self.socket.chart_handler = chart
        with self.assertRaises(ResolutionError):
            self.client.daily_volume("MESZ6", SESSION)
        self.assertEqual(1, len(self.socket.requests("md/cancelChart")))

    def test_missing_eoh_times_out_and_releases_subscriptions(self):
        def chart(request_id, body):
            self.socket.chart(eoh=False)
            self.socket.ack(request_id, {"historicalId": 101, "realtimeId": 102})
        self.socket.chart_handler = chart
        self.assert_code("TIMEOUT", lambda: self.client.daily_volume("MESZ6", SESSION))
        self.assertEqual(2, len(self.socket.requests("md/cancelChart")))
        self.assertGreaterEqual(self.socket.sent.count("[]"), 2)

    def test_missing_ack_closes_socket_to_release_unknown_subscription(self):
        self.socket.chart_handler = lambda request_id, body: None
        self.assert_code("TIMEOUT", lambda: self.client.daily_volume("MESZ6", SESSION))
        self.assertTrue(self.socket.closed)
        self.assertEqual([], self.socket.requests("md/cancelChart"))

    def test_correction_during_cancellation_invalidates_completed_result(self):
        def cancel(request_id, body):
            self.socket.chart(volume=43)
            self.socket.ack(request_id)
        self.socket.cancel_handler = cancel
        with self.assertRaises(ResolutionError):
            self.client.daily_volume("MESZ6", SESSION)
        self.assertEqual(1, len(self.socket.requests("md/cancelChart")))

    def test_failed_cancellation_closes_socket(self):
        self.socket.cancel_handler = lambda request_id, body: None
        self.assertEqual(42, self.client.daily_volume("MESZ6", SESSION))
        self.assertTrue(self.socket.closed)

    def test_early_chart_buffer_overflow_fails_instead_of_using_partial_history(self):
        def chart(request_id, body):
            for _ in range(257):
                self.socket.chart()
            self.socket.ack(request_id, {"historicalId": 101})
        self.socket.chart_handler = chart
        self.assert_code("EARLY_CHART_OVERFLOW", lambda: self.client.daily_volume("MESZ6", SESSION))
        self.assertEqual(1, len(self.socket.requests("md/cancelChart")))

    def test_penalty_ticket_waits_with_heartbeats_and_retries_once(self):
        attempts = []
        def chart(request_id, body):
            attempts.append(body)
            if len(attempts) == 1:
                self.socket.ack(request_id, {"p-ticket": "ticket", "p-time": 6})
            else:
                self.socket.normal_chart(request_id, body)
        self.socket.chart_handler = chart
        self.assertEqual(42, self.client.daily_volume("MESZ6", SESSION))
        self.assertEqual("ticket", attempts[1]["p-ticket"])
        self.assertGreaterEqual(self.clock.now, 6)
        self.assertGreaterEqual(self.socket.sent.count("[]"), int(6 // HEARTBEAT_SECONDS))

    def test_captcha_and_repeated_ticket_are_not_retried_forever(self):
        self.socket.chart_handler = lambda request_id, body: self.socket.ack(request_id, {"p-ticket": "ticket", "p-time": 0, "p-captcha": True})
        self.assert_code("RATE_LIMITED", lambda: self.client.daily_volume("MESZ6", SESSION))
        self.assertEqual(1, len(self.socket.requests("md/getChart")))
        self.socket.chart_handler = lambda request_id, body: self.socket.ack(request_id, {"p-ticket": "ticket", "p-time": 0})
        self.assert_code("RATE_LIMITED", lambda: self.client.daily_volume("MESZ6", SESSION))
        self.assertEqual(3, len(self.socket.requests("md/getChart")))

    def test_simultaneous_chart_limit_has_bounded_backoff(self):
        attempts = []
        def chart(request_id, body):
            attempts.append(body)
            if len(attempts) < 3:
                self.socket.ack(request_id, {"errorText": "Limit for a number of simultaneous chart requests reached"})
            else:
                self.socket.normal_chart(request_id, body)
        self.socket.chart_handler = chart
        self.assertEqual(42, self.client.daily_volume("MESZ6", SESSION))
        self.assertEqual(3, len(attempts))
        self.assertGreaterEqual(self.clock.now, 6)

    def test_authorization_failure_is_global_and_redacted(self):
        self.socket.authorize_error = True
        self.assert_code("AUTH_FAILED", lambda: self.client.daily_volume("MESZ6", SESSION))
        self.assertTrue(self.socket.closed)
        self.assertNotIn(TOKEN, repr(self.client))

    def test_explicit_live_market_gateway_and_optional_market_token(self):
        client = TradovateClient(TOKEN, environment="demo", md_token="other-test-market-token-123", market_environment="live")
        try:
            self.assertEqual(42, client.daily_volume("MESZ6", SESSION))
            self.assertEqual(MARKET_HOSTS["live"], self.factory.call_args.args[0])
            self.assertEqual("other-test-market-token-123", self.socket.sent[0].split("\n\n")[1])
        finally:
            client.close()

    def test_metadata_read_closes_market_connection_and_uses_bearer(self):
        self.assertEqual(42, self.client.daily_volume("MESZ6", SESSION))
        response = Response([{"id": 1, "name": "MES", "productType": "Futures"}])
        with patch.object(self.client._opener, "open", return_value=response) as opener:
            self.assertEqual(1, len(self.client.read("product/list")))
        self.assertTrue(self.socket.closed)
        request = opener.call_args.args[0]
        self.assertEqual("https://demo.tradovateapi.com/v1/product/list", request.full_url)
        self.assertEqual("Bearer " + TOKEN, request.get_header("Authorization"))

    def test_rest_auth_codes_and_raw_errors_are_redacted(self):
        for status, code in ((401, "AUTH_FAILED"), (403, "ACCESS_DENIED"), (429, "RATE_LIMITED"), (500, "HTTP_FAILED")):
            with self.subTest(status=status):
                error = HTTPError("https://example.test", status, TOKEN, {}, io.BytesIO(TOKEN.encode()))
                with patch.object(self.client._opener, "open", side_effect=error):
                    self.assert_code(code, lambda: self.client.read("product/list"))
        with patch.object(self.client._opener, "open", side_effect=RuntimeError(TOKEN)):
            self.assert_code("HTTP_FAILED", lambda: self.client.read("product/list"))

    def test_only_documented_get_metadata_endpoints_are_allowed(self):
        for path in ("order/list", "auth/me", "https://evil.test/product/list", "product/list?x=1", "contract/ldeps?masterids=1,0", "contractMaturity/deps?masterid=1,2", "contract/ldeps?masterids", "../product/list"):
            with self.subTest(path=path):
                self.assert_code("INVALID_ENDPOINT", lambda: self.client.read(path))
        for path in ("product/list", "contractMaturity/deps?masterid=1", "contract/ldeps?masterids=1,2", "tradingSession/ldeps?masterids=1,2"):
            with self.subTest(path=path):
                with patch.object(self.client._opener, "open", return_value=Response([])):
                    self.assertEqual([], self.client.read(path))

    def test_redirect_handler_refuses_to_forward_credentials(self):
        from main_contracts.client import _NoRedirect
        self.assertIsNone(_NoRedirect().redirect_request(None, None, 302, TOKEN, {}, "https://evil.test"))

    def test_delayed_mode_and_invalid_metadata_format_are_rejected(self):
        self.socket.chart_handler = lambda request_id, body: self.socket.ack(request_id, {"historicalId": 101, "mode": "Delayed"})
        self.assert_code("MARKET_DATA_DENIED", lambda: self.client.daily_volume("MESZ6", SESSION))
        with patch.object(self.client._opener, "open", return_value=Response({"errorText": TOKEN})):
            self.assert_code("INVALID_RESPONSE", lambda: self.client.read("product/list"))


if __name__ == "__main__":
    unittest.main()

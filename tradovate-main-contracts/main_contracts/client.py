"""Read-only Tradovate transport; no UniTrading modules or saved credentials."""

from __future__ import annotations

import json
import math
import re
import socket
import time
from decimal import Decimal
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from .core import (
    CompletedSession,
    DailyVolumeHistory,
    ResolutionError,
    chart_key,
    daily_volume_range,
)


MAX_RESPONSE_BYTES = 8_000_000
MAX_EARLY_CHARTS = 256
HEARTBEAT_SECONDS = 2.5
REST_HOSTS = {
    "demo": "https://demo.tradovateapi.com/v1",
    "live": "https://live.tradovateapi.com/v1",
}
MARKET_HOSTS = {
    "demo": "wss://md-demo.tradovateapi.com/v1/websocket",
    "live": "wss://md.tradovateapi.com/v1/websocket",
}


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _connect_socket(url: str, timeout: float):
    try:
        from websocket import create_connection
    except ImportError:
        raise ResolutionError("DEPENDENCY_MISSING", "请先安装 requirements.txt 中的依赖") from None
    return create_connection(url, timeout=timeout, redirect_limit=0)


def _is_timeout(error: Exception) -> bool:
    # The optional dependency is loaded only when real market data is requested.
    return isinstance(error, (TimeoutError, socket.timeout)) or type(error).__name__ == "WebSocketTimeoutException"


def _reject_constant(value):
    raise ValueError("Non-finite JSON number")


def _loads(value):
    # JsonPrimitive in the original code retains a number's lexical precision.
    # Decimal preserves fractional chart IDs and exact integer volume checks.
    return json.loads(value, parse_float=Decimal, parse_constant=_reject_constant)


def _wire_json(value) -> str:
    """Encode cancellation IDs as their original JSON number/string kind."""
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ResolutionError("INVALID_CHANNEL", "行情通道编号无效")
        return str(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(key) + ":" + _wire_json(item) for key, item in value.items()) + "}"
    if isinstance(value, list):
        return "[" + ",".join(_wire_json(item) for item in value) + "]"
    return json.dumps(value, separators=(",", ":"), allow_nan=False)


def _read_path(path: str) -> str:
    """Limit the token-bearing REST client to the four metadata reads we need."""
    if not isinstance(path, str) or len(path) > 4096 or re.search(r"[^A-Za-z0-9/?,=]", path):
        raise ResolutionError("INVALID_ENDPOINT", "不支持的只读 API 路径")
    parts = urlsplit(path)
    try:
        query = parse_qs(parts.query, keep_blank_values=True, strict_parsing=True)
    except ValueError:
        raise ResolutionError("INVALID_ENDPOINT", "不支持的只读 API 参数") from None
    if parts.path == "product/list" and not query:
        return path
    expected = {
        "contractMaturity/deps": "masterid",
        "contract/ldeps": "masterids",
        "tradingSession/ldeps": "masterids",
    }.get(parts.path)
    if expected and set(query) == {expected} and len(query[expected]) == 1:
        ids = query[expected][0].split(",")
        if (expected != "masterid" or len(ids) == 1) and 1 <= len(ids) <= 50:
            if all(re.fullmatch(r"[1-9][0-9]*", value) for value in ids):
                return path
    raise ResolutionError("INVALID_ENDPOINT", "不支持的只读 API 路径或参数")


class TradovateClient:
    """Synchronous, single-caller client, with one outstanding chart at a time.

    Metadata reads close an existing market socket before blocking in urllib.
    The next daily-volume query reconnects, so a long REST call cannot leave a
    market connection unattended without its SockJS heartbeat.
    """

    def __init__(self, token: str, environment: str = "demo", md_token: str | None = None,
                 market_environment: str | None = None, timeout: float = 15):
        if environment not in REST_HOSTS or (market_environment or environment) not in MARKET_HOSTS:
            raise ResolutionError("INVALID_ENVIRONMENT", "环境必须为 demo 或 live")
        for value in (token, md_token):
            if value is not None and (not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._~+/=-]{20,32768}", value)):
                raise ResolutionError("INVALID_TOKEN", "Tradovate token 格式无效")
        if token is None:
            raise ResolutionError("INVALID_TOKEN", "缺少 Tradovate token")
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
            raise ResolutionError("INVALID_TIMEOUT", "请求超时必须为正数")
        self._token = token
        self._market_token = md_token if md_token is not None else token
        self.environment = environment
        self.market_environment = market_environment or environment
        self.timeout = float(timeout)
        self._opener = build_opener(_NoRedirect())
        self._socket = None
        self._sequence = 0
        self._acks: dict[int, dict] = {}
        self._opened = False
        self._next_heartbeat: float | None = None
        self._early: list[dict] = []
        self._overflow = False
        self._collector: DailyVolumeHistory | None = None
        self._channels: set[str] = set()
        self._collection_error: ResolutionError | None = None

    def __repr__(self) -> str:
        return f"TradovateClient(environment={self.environment!r}, credentials=redacted)"

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def close(self):
        current, self._socket = self._socket, None
        self._opened = False
        self._next_heartbeat = None
        self._acks.clear()
        self._early.clear()
        self._channels.clear()
        self._collector = None
        self._collection_error = None
        self._overflow = False
        if current is not None:
            try:
                current.close(timeout=1)
            except Exception:
                pass

    def read(self, path: str) -> list[dict]:
        path = _read_path(path)
        self.close()
        request = Request(f"{REST_HOSTS[self.environment]}/{path}",
                          headers={"Authorization": f"Bearer {self._token}", "Accept": "application/json"},
                          method="GET")
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                if not 200 <= response.status < 300:
                    raise ResolutionError("HTTP_FAILED", "Tradovate 元数据请求失败")
                content = response.read(MAX_RESPONSE_BYTES + 1)
            if len(content) > MAX_RESPONSE_BYTES:
                raise ResolutionError("RESPONSE_TOO_LARGE", "Tradovate 元数据响应过大")
            result = _loads(content)
            if not isinstance(result, list) or not all(isinstance(row, dict) for row in result):
                raise ResolutionError("INVALID_RESPONSE", "Tradovate 元数据返回格式无效")
            return result
        except HTTPError as error:
            status = error.code
            error.close()
            code = {401: "AUTH_FAILED", 403: "ACCESS_DENIED", 429: "RATE_LIMITED"}.get(status, "HTTP_FAILED")
            raise ResolutionError(code, f"Tradovate 元数据请求失败（HTTP {status}）") from None
        except ResolutionError:
            raise
        except Exception:
            raise ResolutionError("HTTP_FAILED", "Tradovate 元数据请求未取得有效结果") from None

    def _send(self, text: str):
        try:
            self._socket.send(text)
        except Exception:
            self.close()
            raise ResolutionError("SOCKET_CLOSED", "Tradovate 行情连接已断开") from None

    def _open_market(self):
        if self._socket is not None:
            return
        try:
            self._socket = _connect_socket(MARKET_HOSTS[self.market_environment], self.timeout)
            deadline = time.monotonic() + self.timeout
            while not self._opened:
                self._pump(deadline)
            self._next_heartbeat = time.monotonic() + HEARTBEAT_SECONDS
            self._request("authorize", self._market_token, raw=True)
        except ResolutionError:
            self.close()
            raise
        except Exception:
            self.close()
            raise ResolutionError("SOCKET_CONNECT_FAILED", "无法连接 Tradovate 行情网关") from None

    def _pump(self, deadline: float):
        now = time.monotonic()
        if now >= deadline:
            raise ResolutionError("TIMEOUT", "Tradovate 行情请求超时")
        if self._next_heartbeat is not None and now >= self._next_heartbeat:
            self._send("[]")
            self._next_heartbeat = now + HEARTBEAT_SECONDS
        timeout = deadline - now
        if self._next_heartbeat is not None:
            timeout = min(timeout, max(0.001, self._next_heartbeat - now))
        try:
            self._socket.settimeout(timeout)
            text = self._socket.recv()
        except Exception as error:
            if _is_timeout(error):
                return
            self.close()
            raise ResolutionError("SOCKET_CLOSED", "Tradovate 行情连接已断开") from None
        if not isinstance(text, str) or not text or text.startswith("c"):
            self.close()
            raise ResolutionError("SOCKET_CLOSED", "Tradovate 行情连接已断开")
        if len(text) > MAX_RESPONSE_BYTES:
            self.close()
            raise ResolutionError("RESPONSE_TOO_LARGE", "Tradovate 行情响应过大")
        if text == "o":
            self._opened = True
            return
        if text in ("h", "[]"):
            return
        try:
            frames = _loads(text[1:]) if text.startswith("a") else [_loads(text)]
            if not isinstance(frames, list) or not all(isinstance(frame, dict) for frame in frames):
                raise ValueError()
            # Process the complete received batch before exposing an ACK or result.
            for frame in frames:
                if frame.get("e") == "chart":
                    data = frame.get("d")
                    charts = data.get("charts") if isinstance(data, dict) else None
                    if not isinstance(charts, list) or not all(isinstance(chart, dict) for chart in charts):
                        raise ValueError()
                    for chart in charts:
                        self._chart(chart)
                elif isinstance(frame.get("i"), int) and not isinstance(frame.get("i"), bool):
                    if frame["i"] == self._sequence:
                        self._acks[frame["i"]] = frame
        except ResolutionError:
            raise
        except Exception:
            self.close()
            raise ResolutionError("INVALID_RESPONSE", "Tradovate 行情返回格式无效") from None

    def _chart(self, packet: dict):
        if self._collector is None:
            if len(self._early) < MAX_EARLY_CHARTS:
                self._early.append(packet)
            else:
                self._overflow = True
            return
        if chart_key(packet.get("id")) in self._channels and self._collection_error is None:
            try:
                self._collector.accept(packet)
            except ResolutionError as error:
                self._collection_error = error

    def _request(self, route: str, body, raw: bool = False, timeout: float | None = None) -> dict:
        if route not in {"authorize", "md/getChart", "md/cancelChart"}:
            raise ResolutionError("INVALID_ENDPOINT", "不支持的行情 API 路径")
        self._sequence += 1
        request_id = self._sequence
        payload = body if raw else _wire_json(body)
        self._send(f"{route}\n{request_id}\n\n{payload}")
        deadline = time.monotonic() + (self.timeout if timeout is None else timeout)
        try:
            while request_id not in self._acks:
                self._pump(deadline)
            frame = self._acks.pop(request_id)
            status, data = frame.get("s"), frame.get("d", {})
            if not isinstance(data, dict):
                raise ResolutionError("INVALID_RESPONSE", "Tradovate 行情回执格式无效")
            if not isinstance(status, int) or isinstance(status, bool) or not 200 <= status < 300 or data.get("errorCode") is not None or data.get("errorText") is not None:
                if route == "authorize":
                    raise ResolutionError("AUTH_FAILED", "Tradovate 行情 token 授权失败")
                if "simultaneous chart" in str(data.get("errorText", "")).lower():
                    raise ResolutionError("CHART_LIMIT", "Tradovate 同时图表请求数量受限")
                raise ResolutionError("MARKET_DATA_DENIED", "Tradovate 行情请求被拒绝，请检查权限或请求频率")
            return data
        except ResolutionError as error:
            if error.code == "TIMEOUT":
                # An uncorrelated late ACK may contain subscription IDs unknown to us.
                self.close()
            raise

    def _pause(self, seconds: float):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                self._pump(deadline)
            except ResolutionError as error:
                if error.code == "TIMEOUT" and time.monotonic() >= deadline:
                    return
                raise

    def _chart_request(self, body: dict) -> dict:
        def request(value):
            for wait in (2, 4):
                try:
                    return self._request("md/getChart", value)
                except ResolutionError as error:
                    if error.code != "CHART_LIMIT":
                        raise
                self._pause(wait)
            return self._request("md/getChart", value)

        result = request(body)
        if "p-ticket" in result:
            seconds, ticket = result.get("p-time"), result.get("p-ticket")
            if result.get("p-captcha") is True or not isinstance(ticket, str) or not ticket:
                raise ResolutionError("RATE_LIMITED", "历史行情限流，需要稍后重试")
            if isinstance(seconds, bool) or not isinstance(seconds, int) or not 0 <= seconds <= 30:
                raise ResolutionError("RATE_LIMITED", "历史行情限流等待时间无效")
            self._pause(seconds)
            result = request({**body, "p-ticket": ticket})
        if "p-ticket" in result:
            raise ResolutionError("RATE_LIMITED", "历史行情查询仍受限，请稍后重试")
        return result

    def daily_volume(self, symbol: str, session: CompletedSession) -> int:
        if (not isinstance(symbol, str) or not symbol.isprintable()
                or not re.fullmatch(r"[^\s@]{1,128}[FGHJKMNQUVXZ][0-9]{1,4}", symbol)):
            raise ResolutionError("INVALID_SYMBOL", "到期合约代码无效")
        self._open_market()
        self._early.clear()
        self._overflow = False
        self._collection_error = None
        ids: dict[str, object] = {}
        collector = None
        try:
            response = self._chart_request({
                "symbol": symbol,
                "chartDescription": {"underlyingType": "DailyBar", "elementSize": 1,
                                     "elementSizeUnit": "UnderlyingUnits", "withHistogram": False},
                "timeRange": daily_volume_range(session),
            })
            for field in ("historicalId", "realtimeId"):
                value = response.get(field)
                key = chart_key(value)
                if key is not None:
                    ids.setdefault(key, value)
            historical = chart_key(response.get("historicalId"))
            realtime = chart_key(response.get("realtimeId"))
            if historical is None:
                raise ResolutionError("INVALID_CHANNEL", "日成交量历史通道缺失或无效")
            mode = response.get("mode")
            if mode not in (None, "RealTime"):
                raise ResolutionError("MARKET_DATA_DENIED", "日成交量行情权限或模式不可用")
            if session.refresh_completed_volume and (mode != "RealTime" or realtime is None):
                raise ResolutionError("MARKET_DATA_DENIED", "核实交易日历末尾需要实时日线权限和通道")
            if self._overflow:
                raise ResolutionError("EARLY_CHART_OVERFLOW", "日成交量历史包丢失，请重试")
            collector = DailyVolumeHistory(session.day, allow_empty=mode == "RealTime")
            self._collector = collector
            self._channels = {historical}
            if session.refresh_completed_volume and realtime is not None:
                self._channels.add(realtime)
            early, self._early = self._early, []
            for packet in early:
                self._chart(packet)
            deadline = time.monotonic() + self.timeout
            while not collector.done and self._collection_error is None:
                self._pump(deadline)
            if self._collection_error is not None:
                raise self._collection_error
        finally:
            # Continue feeding the collector while cancellation ACKs arrive; a
            # correction to the completed date still invalidates the result.
            pending_error = self._collection_error
            for value in ids.values():
                if self._socket is None:
                    break
                try:
                    self._request("md/cancelChart", {"subscriptionId": value}, timeout=min(2, self.timeout))
                except ResolutionError:
                    pending_error = self._collection_error or pending_error
                    self.close()
                    break
                pending_error = self._collection_error or pending_error
            pending_error = self._collection_error or pending_error
            self._collector = None
            self._channels.clear()
            self._early.clear()
            self._collection_error = None
            if pending_error is not None:
                raise pending_error
        if collector is None or collector.volume is None or collector.invalidated:
            raise ResolutionError("INVALID_VOLUME", "未取得有效的完整交易日成交量")
        return collector.volume

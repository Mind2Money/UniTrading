"""Tradovate completed-trading-day volume ranking, without I/O dependencies."""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any


class ResolutionError(Exception):
    """A stable machine-readable code plus a safe, human-readable message."""

    def __init__(self, code: str, message: str):
        self.code = code
        self.message = message
        super().__init__(message)


@dataclass(frozen=True, slots=True)
class CompletedSession:
    day: int
    start_ms: int
    end_ms: int
    valid_until_ms: int
    refresh_completed_volume: bool = False


_DAY_MS = 86_400_000
_EPOCH = datetime(1970, 1, 1, tzinfo=UTC)
_MAX_TIMESTAMP = 253_402_300_799_999
_MAX_VOLUME = 9_223_372_036_854_775_807
_NUMBER = re.compile(r"([+-]?)([0-9]+)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?\Z")
_ROOT = re.compile(r"[^\s@]{1,128}\Z")
_ISO = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}"
    r"(?::[0-9]{2}(?:\.[0-9]{1,9})?)?(?:Z|[+-][0-9]{2}:?[0-9]{2})\Z"
)


def _require(condition: bool, code: str, message: str) -> None:
    if not condition:
        raise ResolutionError(code, message)


def _integer(value: Any, code: str, message: str, *, positive: bool = False) -> int:
    if isinstance(value, bool):
        raise ResolutionError(code, message)
    if isinstance(value, int):
        number = value
    elif isinstance(value, str) and re.fullmatch(r"[+-]?[0-9]{1,20}", value):
        number = int(value)
    else:
        raise ResolutionError(code, message)
    _require(-_MAX_VOLUME - 1 <= number <= _MAX_VOLUME, code, message)
    _require(not positive or number > 0, code, message)
    return number


def timestamp_ms(value: Any) -> int:
    """Parse an epoch-millisecond integer or a timezone-qualified ISO timestamp."""
    if isinstance(value, int) and not isinstance(value, bool):
        result = value
    elif isinstance(value, str) and re.fullmatch(r"[0-9]{1,15}", value.strip()):
        result = int(value.strip())
    else:
        text = value.strip() if isinstance(value, str) else ""
        _require(bool(_ISO.fullmatch(text)), "invalid_timestamp", "时间戳缺失或格式无效")
        try:
            delta = datetime.fromisoformat(text.replace("Z", "+00:00")) - _EPOCH
            result = delta.days * _DAY_MS + delta.seconds * 1000 + delta.microseconds // 1000
        except (ValueError, OverflowError):
            raise ResolutionError("invalid_timestamp", "时间戳缺失或格式无效") from None
    _require(0 <= result <= _MAX_TIMESTAMP, "invalid_timestamp", "时间戳超出支持范围")
    return result


def iso_timestamp(value: int) -> str:
    """Format epoch milliseconds without depending on the host timezone."""
    stamp = _EPOCH + timedelta(milliseconds=timestamp_ms(value))
    return stamp.isoformat(timespec="milliseconds" if stamp.microsecond else "seconds").replace("+00:00", "Z")


def chart_key(value: Any) -> str | None:
    """Canonicalize number/string channel IDs; retain the original ID for cancelChart."""
    if isinstance(value, bool) or not isinstance(value, (str, int, float, Decimal)):
        return None
    text = str(value)
    if not 1 <= len(text) <= 128:
        return None
    match = _NUMBER.fullmatch(text)
    if match is None:
        return "string:" + text if isinstance(value, str) else None
    exponent_text = match[4] or "0"
    if len(exponent_text.lstrip("+-")) > 4:
        return None
    exponent = int(exponent_text)
    if not -1000 <= exponent <= 1000:
        return None
    fraction = match[3] or ""
    digits = (match[2] + fraction).lstrip("0")
    if not digits:
        return "number:0"
    significant = digits.rstrip("0")
    power = exponent - len(fraction) + len(digits) - len(significant)
    sign = "-" if match[1] == "-" else ""
    return f"number:{sign}{significant}e{power}"


def _day_timestamp(day: Any) -> int:
    number = _integer(day, "invalid_trade_date", "交易日期无效")
    text = str(number)
    _require(len(text) == 8, "invalid_trade_date", "交易日期无效")
    try:
        parsed = datetime(int(text[:4]), int(text[4:6]), int(text[6:]), tzinfo=UTC)
    except ValueError:
        raise ResolutionError("invalid_trade_date", "交易日期无效") from None
    return timestamp_ms(parsed.isoformat())


def completed_session(rows: list[dict], maturity_id: int, now_ms: int) -> CompletedSession:
    """Use exchange trade dates and trading end times, including overnight sessions."""
    timestamp_ms(now_ms)
    _require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows),
             "invalid_calendar", "交易日历返回格式无效")
    sessions: list[CompletedSession] = []
    for row in rows:
        try:
            row_maturity = _integer(row.get("contractMaturityId"), "invalid_calendar", "交易时段月份ID无效")
        except ResolutionError:
            continue
        if row_maturity != maturity_id:
            continue
        date = row.get("tradeDate")
        _require(isinstance(date, dict), "invalid_calendar", "交易日历缺少交易日期")
        year, month, day = (
            _integer(date.get(field), "invalid_calendar", "交易日历日期无效")
            for field in ("year", "month", "day")
        )
        _require(1970 <= year <= 9999 and 1 <= month <= 12 and 1 <= day <= 31,
                 "invalid_calendar", "交易日历日期无效")
        trade_day = year * 10_000 + month * 100 + day
        _day_timestamp(trade_day)
        start = timestamp_ms(row.get("startTime"))
        end = timestamp_ms(row.get("endTime"))
        _require(start < end, "invalid_calendar", "交易时段起止无效")
        sessions.append(CompletedSession(trade_day, start, end, 0))
    _require(len({session.day for session in sessions}) == len(sessions),
             "invalid_calendar", "交易日时段不唯一")
    sessions.sort(key=lambda session: session.end_ms)
    _require(all(b.day > a.day and b.start_ms >= a.end_ms for a, b in zip(sessions, sessions[1:])),
             "invalid_calendar", "交易日时段重叠或日期矛盾")
    ended = [session for session in sessions if session.end_ms <= now_ms]
    _require(bool(ended), "missing_completed_session", "缺少上一完整交易日")
    previous = ended[-1]
    following = next((session for session in sessions if session.end_ms > now_ms), None)
    if following is None:
        deadline = previous.end_ms + 72 * 60 * 60 * 1000
        _require(now_ms < deadline, "stale_calendar", "交易日历末尾已超过72小时，等待更新")
        return replace(previous, valid_until_ms=deadline, refresh_completed_volume=True)
    return replace(previous, valid_until_ms=following.end_ms)


def daily_volume_range(session: CompletedSession) -> dict:
    start = _day_timestamp(session.day)
    return {
        "asFarAsTimestamp": iso_timestamp(start),
        "closestTimestamp": iso_timestamp(start + _DAY_MS - 1),
    }


def _volume_value(value: Any) -> int:
    _require(not isinstance(value, bool) and isinstance(value, (str, int, float, Decimal)),
             "invalid_volume", "日成交量缺失或格式无效")
    text = str(value)
    _require(len(text) <= 80 and bool(_NUMBER.fullmatch(text)),
             "invalid_volume", "日成交量缺失或格式无效")
    try:
        number = Decimal(text)
        _require(number.is_finite() and 0 <= number <= _MAX_VOLUME and number == number.to_integral_value(),
                 "invalid_volume", "日成交量必须为非负整数")
        return int(number)
    except InvalidOperation:
        raise ResolutionError("invalid_volume", "日成交量缺失或格式无效") from None


class DailyVolumeHistory:
    """Collect one completed day; an explicit eoh is required, including zero-volume days."""

    def __init__(self, day: int, allow_empty: bool = False):
        self.day = day
        self._target = _day_timestamp(day)
        self.allow_empty = allow_empty
        self.done = False
        self.volume: int | None = None
        self.invalidated = False
        self._saw_older = False
        self._saw_later = False
        self._bars: dict[int, int] = {}

    def accept(self, packet: dict) -> None:
        if self.invalidated:
            return
        try:
            self._accept(packet)
        except ResolutionError:
            self.invalidated = True
            self.done = True
            raise

    def _accept(self, packet: dict) -> None:
        _require(isinstance(packet, dict), "invalid_history", "日线数据格式无效")
        values = packet.get("bars", [])
        _require(isinstance(values, list), "invalid_history", "日线数据格式无效")
        for bar in values:
            _require(isinstance(bar, dict), "invalid_history", "日线数据格式无效")
            time = timestamp_ms(bar.get("timestamp"))
            labels = {
                _integer(item["td"], "invalid_trade_date", "日线交易日期不匹配或无效")
                for item in (packet, bar) if "td" in item
            }
            _require(len(labels) <= 1, "invalid_trade_date", "日线交易日期不匹配或无效")
            if labels:
                date = _day_timestamp(next(iter(labels)))
                _require(time % _DAY_MS != 0 or time == date,
                         "invalid_trade_date", "日线时间戳与交易日期矛盾")
            else:
                _require(time % _DAY_MS == 0, "invalid_trade_date", "无交易日期的日线必须使用UTC零点时间戳")
                date = time
            if date < self._target:
                self._saw_older = True
                continue
            if date > self._target:
                self._saw_later = True
                continue
            count = _volume_value(bar.get("upVolume")) + _volume_value(bar.get("downVolume"))
            _require(count <= _MAX_VOLUME, "invalid_volume", "日成交量总和超出支持范围")
            self._bars[time] = count
        if self.done:
            _require(not self._bars or len(self._bars) == 1 and next(iter(self._bars.values())) == self.volume,
                     "volume_corrected", "已完成日线发生更新，请重新查询")
            return
        if packet.get("eoh") is True:
            if not self._bars and self.allow_empty and (not self._saw_later or self._saw_older):
                self.volume = 0
            else:
                _require(len(self._bars) == 1, "missing_daily_history", "完整交易日日线缺失或不唯一")
                self.volume = next(iter(self._bars.values()))
            self.done = True


def _rows(read: Callable[[str], list[dict]], path: str) -> list[dict]:
    rows = read(path)
    _require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows),
             "invalid_metadata", "合约或交易时段返回格式无效")
    return rows


def select_volume_leader(
    product: dict,
    now_ms: int,
    read: Callable[[str], list[dict]],
    volume: Callable[[str, CompletedSession], int],
) -> dict:
    """Rank every unexpired month serially; never guess from incomplete evidence."""
    _require(isinstance(product, dict), "invalid_product", "品种数据格式无效")
    product_id = _integer(product.get("id"), "invalid_product", "品种ID无效", positive=True)
    root = product.get("name")
    _require(isinstance(root, str) and root.isprintable() and bool(_ROOT.fullmatch(root)),
             "invalid_product", "品种根代码无效")
    timestamp_ms(now_ms)
    all_maturities = _rows(read, f"contractMaturity/deps?masterid={product_id}")
    _require(all(_integer(row.get("productId"), "invalid_metadata", "合约月份品种无效") == product_id
                 for row in all_maturities), "foreign_product", "合约月份品种不匹配")
    maturities = [row for row in all_maturities if timestamp_ms(row.get("expirationDate")) > now_ms]
    _require(0 < len(maturities) <= 200, "invalid_candidates", "候选月份为空或超出完整查询上限")
    by_id = {
        _integer(row.get("id"), "invalid_candidates", "候选月份ID无效", positive=True): row
        for row in maturities
    }
    _require(len(by_id) == len(maturities), "invalid_candidates", "候选月份重复")
    ids = list(by_id)
    contracts: list[dict] = []
    for offset in range(0, len(ids), 50):
        query = ",".join(str(value) for value in ids[offset:offset + 50])
        contracts.extend(_rows(read, f"contract/ldeps?masterids={query}"))
    _require(0 < len(contracts) <= 200, "invalid_candidates", "候选合约为空或超出完整查询上限")
    pattern = re.compile(re.escape(root) + r"[FGHJKMNQUVXZ][0-9]{1,4}\Z")
    contract_ids: set[int] = set()
    maturity_ids: list[int] = []
    for contract in contracts:
        identifier = _integer(contract.get("id"), "invalid_candidates", "候选合约ID无效", positive=True)
        maturity = _integer(contract.get("contractMaturityId"), "invalid_candidates", "候选合约月份ID无效", positive=True)
        name = contract.get("name")
        _require(maturity in by_id and isinstance(name, str) and bool(pattern.fullmatch(name)),
                 "invalid_candidates", "候选合约不完整或品种不匹配")
        _require(identifier not in contract_ids and maturity not in maturity_ids,
                 "invalid_candidates", "候选合约不唯一")
        contract_ids.add(identifier)
        maturity_ids.append(maturity)
    _require(set(maturity_ids) == set(by_id), "incomplete_candidates", "部分候选月份缺少合约，无法完整比较成交量")
    calendar: list[dict] = []
    for offset in range(0, len(maturity_ids), 50):
        query = ",".join(str(value) for value in maturity_ids[offset:offset + 50])
        calendar.extend(_rows(read, f"tradingSession/ldeps?masterids={query}"))
    sessions = {identifier: completed_session(calendar, identifier, now_ms) for identifier in maturity_ids}
    _require(len({session.day for session in sessions.values()}) == 1,
             "inconsistent_trade_date", "候选合约的完整交易日不一致")
    tail = any(session.refresh_completed_volume for session in sessions.values())
    ranked: list[dict] = []
    for contract, maturity_id in zip(contracts, maturity_ids):
        session = replace(sessions[maturity_id], refresh_completed_volume=tail)
        count = _integer(volume(contract["name"], session), "invalid_volume", "日成交量无效")
        _require(count >= 0, "invalid_volume", "日成交量无效")
        ranked.append({"contract_name": contract["name"],
                       "contract_id": _integer(contract["id"], "invalid_candidates", "候选合约ID无效", positive=True),
                       "volume": count})
    maximum = max(row["volume"] for row in ranked)
    _require(maximum > 0, "zero_volume", "上一完整交易日没有有效成交量")
    leaders = [row for row in ranked if row["volume"] == maximum]
    _require(len(leaders) == 1, "tied_volume", "上一完整交易日成交量并列，无法唯一确定主力")
    deadline = min(
        min(session.valid_until_ms for session in sessions.values()),
        min(timestamp_ms(row["expirationDate"]) for row in maturities),
    )
    if tail:
        deadline = min(deadline, now_ms + 15 * 60_000)
    day = next(iter(sessions.values())).day
    day_text = str(day)
    return {
        "continuous_symbol": "@" + root,
        **leaders[0],
        "product_id": product_id,
        "trade_date": f"{day_text[:4]}-{day_text[4:6]}-{day_text[6:]}",
        "checked_at": iso_timestamp(now_ms),
        "valid_until": iso_timestamp(deadline),
        "calendar_tail": tail,
        "candidates": ranked,
    }

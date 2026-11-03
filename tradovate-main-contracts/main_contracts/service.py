"""Discover every visible futures product and resolve each independently."""

from collections.abc import Callable, Iterator
import math
import time

from .client import TradovateClient
from .core import ResolutionError, select_volume_leader, timestamp_ms


def _products(rows: list[dict]) -> list[dict]:
    products = [row for row in rows if row.get("productType") == "Futures"]
    ids, names = set(), set()
    for row in products:
        product_id, name = row.get("id"), row.get("name")
        if (type(product_id) is not int or product_id <= 0 or not isinstance(name, str)
                or not name or len(name) > 128 or name.startswith("@")
                or any(char.isspace() or not char.isprintable() for char in name)):
            raise ResolutionError("INVALID_PRODUCTS", "期货品种目录格式无效")
        if product_id in ids or name in names:
            raise ResolutionError("INVALID_PRODUCTS", "期货品种目录存在重复 ID 或根代码")
        ids.add(product_id)
        names.add(name)
    if not products:
        raise ResolutionError("NO_PRODUCTS", "服务器未返回可见的 Futures 品种")
    return sorted(products, key=lambda row: row["name"])


def _resolve_products(client, *, product_timeout: float,
                      now_ms: Callable[[], int] = lambda: time.time_ns() // 1_000_000,
                      monotonic: Callable[[], float] = time.monotonic) -> Iterator[dict]:
    """Internal injection boundary used by offline integration tests."""
    for product in _products(client.read("product/list")):
        started = now_ms()
        deadline = monotonic() + product_timeout

        def check_deadline():
            if monotonic() >= deadline:
                raise ResolutionError("PRODUCT_TIMEOUT", "该品种查询超过时间上限")

        def read(path):
            check_deadline()
            rows = client.read(path)
            check_deadline()
            return rows

        def volume(symbol, session):
            check_deadline()
            count = client.daily_volume(symbol, session)
            check_deadline()
            return count

        try:
            result = select_volume_leader(product, started, read, volume)
            check_deadline()
            finished = now_ms()
            if finished < started or finished >= timestamp_ms(result["valid_until"]):
                raise ResolutionError("RESULT_EXPIRED", "查询期间结果过期或系统时钟回退，请重新查询")
            yield {"status": "ok", **result}
        except ResolutionError as error:
            if error.code in {"AUTH_FAILED", "ACCESS_DENIED"}:
                raise
            yield {"status": "error", "continuous_symbol": "@" + product["name"],
                   "product_id": product["id"], "code": error.code, "message": error.message}


def resolve_all(token: str, *, environment: str = "demo", md_token: str | None = None,
                market_environment: str | None = None, request_timeout: float = 15,
                product_timeout: float = 180) -> Iterator[dict]:
    """Yield one result per server-visible Futures product; never persist tokens.

    Individual missing entitlements/data produce error records. Catalog and session
    authorization failures raise ResolutionError. Consume/close this generator to
    release its connection. Every successful record has its own validity deadline.
    """
    for value in (request_timeout, product_timeout):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            raise ResolutionError("INVALID_TIMEOUT", "查询超时必须是有限正数")
    with TradovateClient(token, environment=environment, md_token=md_token,
                         market_environment=market_environment, timeout=request_timeout) as client:
        yield from _resolve_products(client, product_timeout=product_timeout)

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from main_contracts.__main__ import main
from main_contracts.core import ResolutionError, timestamp_ms
from main_contracts.service import _resolve_products


NOW = timestamp_ms("2026-11-03T06:00:00Z")


class CatalogClient:
    def __init__(self, fail=None):
        self.fail = fail
        self.products = [
            {"id": 1, "name": "MES", "productType": "Futures", "currencyId": 1},
            {"id": 2, "name": "GC", "productType": "Futures", "currencyId": 2},
            {"id": 3, "name": "OPT", "productType": "Options"},
        ]
        self.volume_calls = []

    def read(self, path):
        if path == "product/list":
            return self.products
        if path.startswith("contractMaturity/deps?"):
            product_id = int(path.split("=")[1])
            if product_id == 2 and self.fail:
                raise ResolutionError(self.fail, "不可访问")
            return [{"id": product_id * 10 + month, "productId": product_id,
                     "expirationDate": "2027-03-20T00:00:00Z"} for month in (0, 1)]
        ids = [int(value) for value in path.split("=")[1].split(",")]
        if path.startswith("contract/ldeps?"):
            return [{"id": maturity_id + 100, "contractMaturityId": maturity_id,
                     "name": ("MES" if maturity_id // 10 == 1 else "GC") +
                     ("Z6" if maturity_id % 10 == 0 else "H7")} for maturity_id in ids]
        if path.startswith("tradingSession/ldeps?"):
            return [{"contractMaturityId": maturity_id,
                     "tradeDate": {"year": 2026, "month": 11, "day": day},
                     "startTime": f"2026-11-0{day - 1}T23:00:00Z",
                     "endTime": f"2026-11-0{day}T22:00:00Z"}
                    for maturity_id in ids for day in (2, 3)]
        raise AssertionError(path)

    def daily_volume(self, symbol, session):
        self.volume_calls.append(symbol)
        return 1000 if symbol.endswith("H7") else 10


class ServiceTests(unittest.TestCase):
    def resolve(self, client, **kwargs):
        return list(_resolve_products(client, product_timeout=180,
                                      now_ms=kwargs.pop("now_ms", lambda: NOW), **kwargs))

    def test_all_visible_futures_and_currencies_rank_independently(self):
        client = CatalogClient()
        records = self.resolve(client)
        self.assertEqual([row["continuous_symbol"] for row in records], ["@GC", "@MES"])
        self.assertEqual([row["contract_name"] for row in records], ["GCH7", "MESH7"])
        self.assertEqual(set(client.volume_calls), {"GCZ6", "GCH7", "MESZ6", "MESH7"})
        self.assertTrue(all(row["status"] == "ok" and row["trade_date"] == "2026-11-02" for row in records))

    def test_one_product_failure_does_not_hide_other_results(self):
        records = self.resolve(CatalogClient("MARKET_DATA_DENIED"))
        self.assertEqual([row["status"] for row in records], ["error", "ok"])
        self.assertNotIn("contract_name", records[0])
        self.assertEqual(records[1]["contract_name"], "MESH7")

    def test_global_authorization_failure_stops_batch(self):
        with self.assertRaises(ResolutionError) as raised:
            self.resolve(CatalogClient("AUTH_FAILED"))
        self.assertEqual(raised.exception.code, "AUTH_FAILED")

    def test_cross_session_result_cannot_be_emitted_as_success(self):
        client = CatalogClient()
        client.products = client.products[:1]
        times = iter([NOW, timestamp_ms("2026-11-03T22:00:00Z")])
        records = self.resolve(client, now_ms=lambda: next(times))
        self.assertEqual(records[0]["code"], "RESULT_EXPIRED")

    def test_product_timeout_does_not_continue_querying_months(self):
        client = CatalogClient()
        client.products = client.products[:1]
        ticks = iter([0, 181])
        records = self.resolve(client, monotonic=lambda: next(ticks))
        self.assertEqual(records[0]["code"], "PRODUCT_TIMEOUT")
        self.assertEqual(client.volume_calls, [])

    def test_duplicate_or_empty_catalog_is_explicit_failure(self):
        for products in ([], [{"id": 1, "name": "ES", "productType": "Futures"}] * 2):
            client = CatalogClient()
            client.products = products
            with self.assertRaises(ResolutionError):
                self.resolve(client)


class CliTests(unittest.TestCase):
    def invoke(self, records, *args, env=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with (patch.dict(os.environ, {"TRADOVATE_TOKEN": "test-token", **(env or {})}, clear=True),
              patch("main_contracts.__main__.resolve_all", return_value=(row for row in records)) as resolve,
              contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr)):
            status = main(list(args))
        return status, stdout.getvalue(), stderr.getvalue(), resolve

    def test_jsonl_partial_failure_is_nonzero_and_has_summary(self):
        status, output, errors, _ = self.invoke([
            {"status": "ok", "continuous_symbol": "@MES", "contract_name": "MESH7"},
            {"status": "error", "continuous_symbol": "@GC", "code": "TIE", "message": "并列"}])
        rows = [json.loads(line) for line in output.splitlines()]
        self.assertEqual(status, 2)
        self.assertEqual(rows[-1], {"status": "summary", "succeeded": 1, "failed": 1, "complete": True})
        self.assertEqual(errors, "")
        self.assertNotIn("test-token", output)

    def test_names_output_has_only_mappings_and_diagnostics_on_stderr(self):
        status, output, errors, _ = self.invoke([
            {"status": "ok", "continuous_symbol": "@MES", "contract_name": "MESH7"},
            {"status": "error", "continuous_symbol": "@GC", "code": "TIE", "message": "并列"}],
            "--format", "names")
        self.assertEqual((status, output), (2, "@MES=MESH7\n"))
        self.assertIn('"complete": true', errors)

    def test_stdin_token_and_optional_market_gateway_reach_api(self):
        with patch("sys.stdin", io.StringIO("stdin-token\n")):
            status, _, _, resolve = self.invoke([], "--token-stdin", "--environment", "demo",
                                               "--market-environment", "live",
                                               env={"TRADOVATE_MD_TOKEN": "market-token"})
        self.assertEqual(status, 0)
        self.assertEqual(resolve.call_args.args, ("stdin-token",))
        self.assertEqual(resolve.call_args.kwargs["md_token"], "market-token")
        self.assertEqual(resolve.call_args.kwargs["market_environment"], "live")

    def test_output_file_is_utf8_and_separate_from_terminal(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "results.jsonl"
            status, output, _, _ = self.invoke([
                {"status": "error", "code": "TIE", "message": "成交量并列"}], "--output", str(path))
            self.assertEqual((status, output), (2, ""))
            self.assertEqual(json.loads(path.read_text(encoding="utf-8").splitlines()[0])["message"], "成交量并列")

    def test_fatal_failure_is_incomplete_with_no_traceback_or_token(self):
        def broken(*args, **kwargs):
            yield {"status": "ok", "continuous_symbol": "@MES", "contract_name": "MESH7"}
            raise ResolutionError("AUTH_FAILED", "令牌已失效")

        output = io.StringIO()
        with (patch.dict(os.environ, {"TRADOVATE_TOKEN": "secret-value"}, clear=True),
              patch("main_contracts.__main__.resolve_all", side_effect=broken),
              contextlib.redirect_stdout(output)):
            self.assertEqual(main([]), 1)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertFalse(rows[-1]["complete"])
        self.assertEqual(rows[-2]["code"], "AUTH_FAILED")
        self.assertNotIn("secret-value", output.getvalue())


if __name__ == "__main__":
    unittest.main()

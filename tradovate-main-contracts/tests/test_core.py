"""Deterministic algorithm regressions; no token or network access is required."""

import copy
import unittest
from decimal import Decimal

from main_contracts.core import (
    DailyVolumeHistory,
    ResolutionError,
    chart_key,
    completed_session,
    daily_volume_range,
    iso_timestamp,
    select_volume_leader,
    timestamp_ms,
)


def session(maturity, day=2, start=None, end=None):
    return {
        "contractMaturityId": maturity,
        "tradeDate": {"year": 2026, "month": 11, "day": day},
        "startTime": start or f"2026-11-{day - 1:02}T23:00Z",
        "endTime": end or f"2026-11-{day:02}T22:00Z",
        "openTime": "2026-11-01T22:30Z",
        "closeTime": "2026-11-03T22:29Z",
    }


def bar(day=2, up=100, down=5, **extra):
    return {"timestamp": f"2026-11-{day:02}T00:00Z", "upVolume": up, "downVolume": down, **extra}


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.now = timestamp_ms("2026-11-03T12:00Z")
        self.product = {"id": 400, "name": "MES"}
        self.maturities = [
            {"id": 300, "productId": 400, "expirationDate": "2026-12-18T22:00Z", "isFront": True},
            {"id": 301, "productId": 400, "expirationDate": "2027-03-19T22:00Z", "isFront": False},
        ]
        self.contracts = [
            {"id": 200, "name": "MESZ6", "contractMaturityId": 300},
            {"id": 201, "name": "MESH7", "contractMaturityId": 301},
        ]
        self.calendar = [session(identifier, day) for identifier in (300, 301) for day in (2, 3)]
        self.requests = []
        self.volumes = {"MESZ6": 10, "MESH7": 1000}

    def read(self, path):
        self.requests.append(path)
        if path.startswith("contractMaturity/deps?"):
            return copy.deepcopy(self.maturities)
        if path.startswith("contract/ldeps?"):
            return copy.deepcopy(self.contracts)
        if path.startswith("tradingSession/ldeps?"):
            return copy.deepcopy(self.calendar)
        self.fail(path)

    def resolve(self, get_volume=None):
        return select_volume_leader(self.product, self.now, self.read,
                                    get_volume or (lambda name, _: self.volumes[name]))

    def test_root_metadata_is_escaped_rather_than_limited_to_alphanumeric(self):
        self.product["name"] = "TEST.ROOT"
        for contract in self.contracts:
            contract["name"] = contract["name"].replace("MES", "TEST.ROOT")
        result = self.resolve(lambda name, _: 100 if name.endswith("H7") else 10)
        self.assertEqual(result["contract_name"], "TEST.ROOTH7")
        self.contracts[0]["name"] = "TESTxROOTZ6"
        with self.assertRaises(ResolutionError):
            self.resolve(lambda name, _: 100)

    def test_far_month_wins_over_is_front_and_output_contains_evidence(self):
        result = self.resolve()
        self.assertEqual("MESH7", result["contract_name"])
        self.assertEqual("@MES", result["continuous_symbol"])
        self.assertEqual(201, result["contract_id"])
        self.assertEqual(400, result["product_id"])
        self.assertEqual("2026-11-02", result["trade_date"])
        self.assertEqual(1000, result["volume"])
        self.assertEqual("2026-11-03T12:00:00Z", result["checked_at"])
        self.assertEqual("2026-11-03T22:00:00Z", result["valid_until"])
        self.assertFalse(result["calendar_tail"])
        self.assertEqual(2, len(result["candidates"]))
        self.assertEqual(3, len(self.requests))

    def test_each_invocation_reads_every_candidate_without_cache(self):
        calls = []
        def get_volume(name, target):
            calls.append((name, target.day))
            return self.volumes[name]
        self.resolve(get_volume)
        self.resolve(get_volume)
        self.assertEqual([("MESZ6", 20261102), ("MESH7", 20261102)] * 2, calls)

    def test_ties_zero_negative_fraction_and_boolean_cannot_rank(self):
        for counts, code in [((10, 10), "tied_volume"), ((0, 0), "zero_volume"),
                             ((-1, 10), "invalid_volume"), ((1.5, 10), "invalid_volume"),
                             ((True, 10), "invalid_volume")]:
            with self.subTest(counts=counts):
                self.volumes = dict(zip(("MESZ6", "MESH7"), counts))
                with self.assertRaises(ResolutionError) as error:
                    self.resolve()
                self.assertEqual(code, error.exception.code)

    def test_incomplete_duplicate_foreign_and_malformed_candidates_fail(self):
        original = copy.deepcopy(self.contracts)
        cases = [original[:1], original + original[:1],
                 [dict(original[0], name="ESZ6"), original[1]],
                 [dict(original[0], contractMaturityId=999), original[1]],
                 [dict(original[0], id=True), original[1]], [None], []]
        for contracts in cases:
            with self.subTest(contracts=contracts):
                self.contracts = contracts
                with self.assertRaises(ResolutionError):
                    self.resolve()

    def test_maturity_product_duplicate_invalid_expiry_and_limit_fail(self):
        original = copy.deepcopy(self.maturities)
        cases = [[dict(original[0], productId=500), original[1]], original + original[:1],
                 [dict(original[0], expirationDate=None), original[1]], [], original * 101]
        for maturities in cases:
            with self.subTest(maturities=maturities[:2]):
                self.maturities = maturities
                with self.assertRaises(ResolutionError):
                    self.resolve()

    def test_expired_months_are_excluded_before_reading_contracts(self):
        self.maturities.append({"id": 299, "productId": 400, "expirationDate": "2026-11-03T12:00Z"})
        self.assertEqual("MESH7", self.resolve()["contract_name"])
        self.assertEqual("contract/ldeps?masterids=300,301", self.requests[1])

    def test_string_integer_metadata_matches_numeric_ids(self):
        self.contracts[1]["id"] = "201"
        self.contracts[1]["contractMaturityId"] = "301"
        self.calendar = [{**row, "contractMaturityId": str(row["contractMaturityId"])} for row in self.calendar]
        self.assertEqual(201, self.resolve()["contract_id"])

    def test_contract_and_session_reads_batch_at_fifty_maturity_ids(self):
        self.maturities = [{"id": 300 + index, "productId": 400,
                            "expirationDate": "2027-03-19T22:00Z"} for index in range(51)]
        self.contracts = [{"id": 200 + index, "name": f"MESH{index + 1}",
                           "contractMaturityId": 300 + index} for index in range(51)]
        self.calendar = [session(300 + index, day) for index in range(51) for day in (2, 3)]
        def read(path):
            self.requests.append(path)
            if path.startswith("contractMaturity/"):
                return self.maturities
            ids = set(map(int, path.split("masterids=")[1].split(",")))
            self.assertLessEqual(len(ids), 50)
            rows = self.contracts if path.startswith("contract/") else self.calendar
            return [row for row in rows if row["contractMaturityId"] in ids]
        result = select_volume_leader(self.product, self.now, read,
                                      lambda name, _: 100 if name == "MESH51" else 1)
        self.assertEqual("MESH51", result["contract_name"])
        self.assertEqual(51, len(result["candidates"]))
        self.assertEqual(5, len(self.requests))

    def test_earliest_candidate_expiration_caps_validity_even_if_not_leader(self):
        self.maturities[0]["expirationDate"] = "2026-11-03T14:00Z"
        self.assertEqual("2026-11-03T14:00:00Z", self.resolve()["valid_until"])

    def test_different_completed_trade_dates_fail_before_any_volume_call(self):
        self.calendar = [session(300, 1, start="2026-10-31T23:00Z"), session(300, 3), session(301, 2), session(301, 3)]
        calls = []
        with self.assertRaises(ResolutionError) as error:
            self.resolve(lambda *args: calls.append(args))
        self.assertEqual("inconsistent_trade_date", error.exception.code)
        self.assertEqual([], calls)

    def test_tail_on_one_candidate_requires_tail_mode_for_all_and_fifteen_minute_cap(self):
        self.calendar = [row for row in self.calendar if not (row["contractMaturityId"] == 300 and row["tradeDate"]["day"] == 3)]
        modes = []
        def get_volume(name, target):
            modes.append(target.refresh_completed_volume)
            return self.volumes[name]
        result = self.resolve(get_volume)
        self.assertEqual([True, True], modes)
        self.assertTrue(result["calendar_tail"])
        self.assertEqual("2026-11-03T12:15:00Z", result["valid_until"])

    def test_day_boundary_uses_exchange_end_even_on_early_close(self):
        rows = [session(1, 2), session(1, 3, end="2026-11-03T18:15Z"), session(1, 4)]
        end = timestamp_ms("2026-11-03T18:15Z")
        previous = completed_session(rows, 1, end - 1)
        self.assertEqual(20261102, previous.day)
        self.assertEqual(end, previous.valid_until_ms)
        self.assertEqual(20261103, completed_session(rows, 1, end).day)

    def test_tail_seventy_two_hour_limit_and_weekend_need_no_local_date_guess(self):
        rows = [{**session(1), "tradeDate": {"year": 2026, "month": 10, "day": 30},
                 "startTime": "2026-10-29T22:00Z", "endTime": "2026-10-30T21:00Z"}]
        end = timestamp_ms("2026-10-30T21:00Z")
        result = completed_session(rows, 1, end + 24 * 3600_000)
        self.assertEqual(20261030, result.day)
        self.assertTrue(result.refresh_completed_volume)
        self.assertEqual(end + 72 * 3600_000, result.valid_until_ms)
        completed_session(rows, 1, end + 72 * 3600_000 - 1)
        with self.assertRaises(ResolutionError) as error:
            completed_session(rows, 1, end + 72 * 3600_000)
        self.assertEqual("stale_calendar", error.exception.code)

    def test_invalid_calendar_missing_duplicate_overlap_reversal_and_date(self):
        cases = [[], [session(1), session(1)], [session(1), session(1, 3, start="2026-11-01T23:00Z")],
                 [session(1, start="2026-11-03T22:00Z")],
                 [{**session(1), "tradeDate": {"year": 2026, "month": 2, "day": 30}}], [None]]
        for rows in cases:
            with self.subTest(rows=rows):
                with self.assertRaises(ResolutionError):
                    completed_session(rows, 1, self.now)

    def test_volume_range_is_utc_trade_date_not_session_hours(self):
        target = completed_session([session(1, start="2026-11-02T01:00Z", end="2026-11-02T09:00Z")], 1, self.now)
        self.assertEqual({"asFarAsTimestamp": "2026-11-02T00:00:00Z",
                          "closestTimestamp": "2026-11-02T23:59:59.999Z"}, daily_volume_range(target))

    def test_collector_waits_for_eoh_and_deduplicates_same_bar(self):
        history = DailyVolumeHistory(20261102)
        history.accept({"bars": [bar()]})
        history.accept({"bars": [bar()]})
        self.assertFalse(history.done)
        self.assertIsNone(history.volume)
        history.accept({"eoh": True})
        self.assertTrue(history.done)
        self.assertEqual(105, history.volume)

    def test_collector_ignores_current_volume_but_checks_dates(self):
        history = DailyVolumeHistory(20261102)
        history.accept({"bars": [bar(3, up=100000, down=None), bar()], "eoh": True})
        self.assertEqual(105, history.volume)
        for current in [bar(3, timestamp="2026-11-03T01:00Z"), bar(3, td=20261104), bar(3, td=None)]:
            with self.subTest(current=current):
                invalid = DailyVolumeHistory(20261102)
                with self.assertRaises(ResolutionError):
                    invalid.accept({"bars": [bar(), current], "eoh": True})
                self.assertTrue(invalid.invalidated)

    def test_collector_zero_requires_realtime_and_ended_empty_or_older_evidence(self):
        for values in [[], [bar(1)], [bar(1), bar(3)]]:
            with self.subTest(values=values):
                history = DailyVolumeHistory(20261102, allow_empty=True)
                history.accept({"bars": values})
                self.assertFalse(history.done)
                history.accept({"eoh": True})
                self.assertEqual(0, history.volume)
                with self.assertRaises(ResolutionError):
                    DailyVolumeHistory(20261102).accept({"bars": values, "eoh": True})
        with self.assertRaises(ResolutionError):
            DailyVolumeHistory(20261102, allow_empty=True).accept({"bars": [bar(3)], "eoh": True})

    def test_collector_dates_and_required_volume_fields_are_strict(self):
        invalid_bars = [bar(timestamp="2026-11-02T01:00Z"), bar(td=None), bar(td=20261103),
                        bar(up=None), bar(down=-1), bar(up="0.5"), bar(up=True),
                        bar(up=9223372036854775807, down=1), None]
        for invalid_bar in invalid_bars:
            with self.subTest(bar=invalid_bar):
                history = DailyVolumeHistory(20261102, allow_empty=True)
                with self.assertRaises(ResolutionError):
                    history.accept({"bars": [invalid_bar], "eoh": True})
                self.assertTrue(history.invalidated)
        for values in [None, {}, "", 1]:
            with self.assertRaises(ResolutionError):
                DailyVolumeHistory(20261102, allow_empty=True).accept({"bars": values, "eoh": True})

    def test_matching_explicit_trade_date_allows_non_midnight_and_decimal_integer(self):
        history = DailyVolumeHistory(20261102)
        history.accept({"td": 20261102, "bars": [bar(td=20261102, timestamp="2026-11-02T01:00Z", up="1e2", down=Decimal("5.0"))], "eoh": True})
        self.assertEqual(105, history.volume)
        with self.assertRaises(ResolutionError):
            DailyVolumeHistory(20261102).accept({"td": 20261102, "bars": [bar(td=20261103)], "eoh": True})

    def test_collector_detects_target_correction_after_completion_not_current_bar(self):
        history = DailyVolumeHistory(20261102, allow_empty=True)
        history.accept({"bars": [bar()], "eoh": True})
        history.accept({"bars": [bar(3)]})
        self.assertFalse(history.invalidated)
        with self.assertRaises(ResolutionError) as error:
            history.accept({"bars": [bar(up=101)]})
        self.assertEqual("volume_corrected", error.exception.code)
        self.assertTrue(history.invalidated)
        zero = DailyVolumeHistory(20261102, allow_empty=True)
        zero.accept({"eoh": True})
        zero.accept({"bars": [bar(3)]})
        self.assertEqual(0, zero.volume)
        with self.assertRaises(ResolutionError):
            zero.accept({"bars": [bar()]})

    def test_chart_ids_preserve_fractional_equivalence_and_reject_invalid_types(self):
        for value in [12.5, "12.50", Decimal("125e-1")]:
            self.assertEqual("number:125e-1", chart_key(value))
        self.assertEqual(chart_key(-0.0), chart_key("0"))
        self.assertEqual("string:chart-A", chart_key("chart-A"))
        for value in [True, None, [], {}, "", "x" * 129, float("nan"), Decimal("Infinity"), "1e1001"]:
            self.assertIsNone(chart_key(value))

    def test_timestamps_are_timezone_aware_and_accept_reduced_seconds(self):
        self.assertEqual(self.now, timestamp_ms("2026-11-03T20:00+0800"))
        self.assertEqual("2026-11-03T12:00:00.123Z", iso_timestamp(self.now + 123))
        for value in [True, None, "2026-11-03T12:00", "2026-02-30T12:00Z"]:
            with self.assertRaises(ResolutionError):
                timestamp_ms(value)


if __name__ == "__main__":
    unittest.main()

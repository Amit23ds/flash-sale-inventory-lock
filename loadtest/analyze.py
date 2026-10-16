#!/usr/bin/env python3
"""Summarise a JMeter CSV results file (JTL) and check the flash-sale invariants.

Standard library only. Usage:

    python3 loadtest/analyze.py results.jtl \
        --initial-stock 100 --final-stock 0 --orders 100

Exit codes: 0 = all checks pass, 1 = an invariant failed, 2 = unreadable input.
"""

import argparse
import csv
import math
import statistics
import sys
from collections import Counter

EXPECTED_CODES = {"200", "409", "429"}


def percentile(sorted_values, p):
    """Nearest-rank percentile: the smallest value with at least p% of samples at or below it."""
    rank = math.ceil(p / 100 * len(sorted_values))
    return sorted_values[max(rank, 1) - 1]


def peak_in_flight(intervals):
    """Largest number of requests whose [start, end) intervals overlap at one instant.

    Intervals are half-open: a request ending at t and another starting at t do not overlap,
    so a thread that sends its next request in the same millisecond is not counted twice.
    """
    events = []
    for start, end in intervals:
        events.append((end, 0))  # 0 sorts before 1, so ends are processed before starts on ties.
        events.append((start, 1))
    in_flight = peak = 0
    for _, kind in sorted(events):
        in_flight += 1 if kind == 1 else -1
        peak = max(peak, in_flight)
    return peak


def in_flight_intervals(samples):
    """[start, end) per sample, with each start clamped to its thread's previous end.

    A JMeter thread sends one request at a time, but JMeter derives timeStamp from a
    nanoTime-to-wall-clock offset that a background thread recalculates (every 5 s by
    default). When the offset moves, a thread's next start can appear up to a millisecond
    before its previous end. Returns the intervals and how many starts were clamped.
    """
    intervals = []
    previous_end = {}
    clamped = 0
    for start, elapsed, _, thread in sorted(samples, key=lambda s: (s[3], s[0])):
        end = start + elapsed
        floor = previous_end.get(thread)
        if thread is not None and floor is not None and start < floor:
            start, end, clamped = floor, max(end, floor), clamped + 1
        previous_end[thread] = end
        intervals.append((start, end))
    return intervals, clamped


def read_samples(path):
    with open(path, newline="") as handle:
        reader = csv.DictReader(handle)
        missing = {"timeStamp", "elapsed", "responseCode"} - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"missing JTL columns: {', '.join(sorted(missing))} "
                             "(expected CSV output with field names)")
        return [(int(row["timeStamp"]), int(row["elapsed"]), row["responseCode"], row.get("threadName"))
                for row in reader]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jtl", help="JMeter results file in CSV format")
    parser.add_argument("--initial-stock", type=int, help="total stock seeded before the run")
    parser.add_argument("--final-stock", type=int, help="total stock left after the run")
    parser.add_argument("--orders", type=int, help="orders persisted after the run")
    args = parser.parse_args()

    stock_args = (args.initial_stock, args.final_stock, args.orders)
    if any(v is not None for v in stock_args) and None in stock_args:
        parser.error("--initial-stock, --final-stock and --orders must be given together")

    try:
        samples = read_samples(args.jtl)
    except (OSError, ValueError) as error:
        print(f"analyze.py: cannot read {args.jtl}: {error}", file=sys.stderr)
        return 2
    if not samples:
        print(f"analyze.py: {args.jtl} contains no samples", file=sys.stderr)
        return 2

    codes = Counter(code for _, _, code, _ in samples)
    unexpected = sum(n for code, n in codes.items() if code not in EXPECTED_CODES)
    latencies = sorted(elapsed for _, elapsed, _, _ in samples)
    intervals = [(start, start + elapsed) for start, elapsed, _, _ in samples]
    flight_intervals, clamped = in_flight_intervals(samples)
    first_start = min(start for start, _ in intervals)
    last_end = max(end for _, end in intervals)
    duration_s = max(last_end - first_start, 1) / 1000
    mean_ms = statistics.fmean(latencies)
    throughput = len(samples) / duration_s

    print(f"Load test summary: {args.jtl}")
    print(f"  Requests              {len(samples)}")
    print(f"  Wall-clock duration   {duration_s:.3f} s")
    print("Response codes")
    for code, n in sorted(codes.items()):
        flag = "" if code in EXPECTED_CODES else "   <- unexpected"
        print(f"  {code:<20}  {n}{flag}")
    print(f"  {'unexpected total':<20}  {unexpected}")
    print("Latency (ms)")
    print(f"  min {latencies[0]}  mean {mean_ms:.1f}  p50 {percentile(latencies, 50)}  "
          f"p90 {percentile(latencies, 90)}  p95 {percentile(latencies, 95)}  "
          f"p99 {percentile(latencies, 99)}  max {latencies[-1]}")
    print("Throughput")
    print(f"  All requests          {throughput:.1f} req/s")
    print(f"  Confirmed (200)       {codes['200'] / duration_s:.1f} purchases/s")
    print("Concurrency")
    clamp_note = f"   ({clamped} start(s) clamped to the thread's previous end)" if clamped else ""
    print(f"  Peak in-flight        {peak_in_flight(flight_intervals)}{clamp_note}")
    print(f"  Little's law average  {throughput * mean_ms / 1000:.2f}   (throughput x mean latency)")

    checks = [("unexpected response codes == 0", unexpected == 0, f"{unexpected}")]
    if args.initial_stock is not None:
        sold = args.initial_stock - args.final_stock
        checks += [
            ("finalStock >= 0", args.final_stock >= 0, f"finalStock={args.final_stock}"),
            ("orders == initialStock - finalStock", args.orders == sold,
             f"{args.orders} == {args.initial_stock} - {args.final_stock}"),
            ("count(200) == orders", codes["200"] == args.orders, f"{codes['200']} == {args.orders}"),
        ]
    else:
        print("(stock invariants skipped: pass --initial-stock, --final-stock and --orders)")

    print("Invariants")
    for name, ok, detail in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name:<38} ({detail})")
    passed = all(ok for _, ok, _ in checks)
    print(f"RESULT: {'PASS' if passed else 'FAIL'}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())

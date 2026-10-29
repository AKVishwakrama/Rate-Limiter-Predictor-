#!/usr/bin/env python3
"""
Phase 2: Traffic simulator.

Generates request load against the Phase 1 gateway following one of four
shapes: steady, burst, ramp, sustained_high. Each shape produces a
per-second target request rate; the simulator fires that many requests
each second using a thread pool, and logs every response (status + client
latency) to its own CSV so you have a client-side view to cross-check
against the server-side metrics.csv the gateway writes.

Usage:
    python simulator.py --pattern burst --duration 60 --clients 5 \
        --base-rate 5 --out results/burst_token_bucket.csv

Run the SAME pattern/duration/seed against both strategies (switch strategy
via the gateway's /admin/strategy endpoint between runs) so the comparison
in analyze_results.py is apples-to-apples.
"""

import argparse
import csv
import random
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

GATEWAY_URL = "http://localhost:8080/api/resource"


def rate_for_second(pattern: str, second: int, duration: int,
                     base_rate: int, burst_multiplier: int) -> int:
    """Return target requests-per-second for this second, given the pattern."""
    if pattern == "steady":
        return base_rate

    if pattern == "burst":
        # Baseline traffic, with a short sharp spike in the middle third.
        burst_start = duration // 3
        burst_end = burst_start + max(3, duration // 10)
        if burst_start <= second < burst_end:
            return base_rate * burst_multiplier
        return base_rate

    if pattern == "ramp":
        # Linearly increase from base_rate to base_rate * burst_multiplier.
        progress = second / max(1, duration - 1)
        return int(base_rate + progress * (base_rate * (burst_multiplier - 1)))

    if pattern == "sustained_high":
        return base_rate * burst_multiplier

    raise ValueError(f"Unknown pattern: {pattern}")


def fire_request(client_id: str) -> dict:
    start = time.perf_counter()
    try:
        resp = requests.get(GATEWAY_URL, params={"clientId": client_id}, timeout=5)
        latency_ms = (time.perf_counter() - start) * 1000
        return {
            "timestamp_ms": int(time.time() * 1000),
            "client_id": client_id,
            "status_code": resp.status_code,
            "allowed": resp.status_code == 200,
            "client_latency_ms": round(latency_ms, 2),
        }
    except requests.RequestException as e:
        latency_ms = (time.perf_counter() - start) * 1000
        return {
            "timestamp_ms": int(time.time() * 1000),
            "client_id": client_id,
            "status_code": -1,
            "allowed": False,
            "client_latency_ms": round(latency_ms, 2),
            "error": str(e),
        }


def run(pattern: str, duration: int, clients: int, base_rate: int,
        burst_multiplier: int, out_path: str):
    client_ids = [f"client-{i}" for i in range(clients)]
    rows = []

    print(f"Starting '{pattern}' pattern for {duration}s, "
          f"base_rate={base_rate} req/s, burst_multiplier={burst_multiplier}, "
          f"clients={clients}")

    with ThreadPoolExecutor(max_workers=max(50, base_rate * burst_multiplier * 2)) as pool:
        for second in range(duration):
            target_rate = rate_for_second(pattern, second, duration, base_rate, burst_multiplier)
            futures = []
            tick_start = time.perf_counter()

            for _ in range(target_rate):
                client_id = random.choice(client_ids)
                futures.append(pool.submit(fire_request, client_id))

            for f in futures:
                rows.append(f.result())

            # Keep to roughly 1 second per tick regardless of how long the
            # batch took, so the intended req/sec rate is honoured.
            elapsed = time.perf_counter() - tick_start
            if elapsed < 1.0:
                time.sleep(1.0 - elapsed)

            print(f"  second={second:3d}  target_rate={target_rate:4d}  "
                  f"sent={len(futures):4d}")

    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "timestamp_ms", "client_id", "status_code", "allowed",
            "client_latency_ms", "error"
        ])
        writer.writeheader()
        for row in rows:
            row.setdefault("error", "")
            writer.writerow(row)

    total = len(rows)
    allowed = sum(1 for r in rows if r["allowed"])
    print(f"\nDone. {total} requests sent, {allowed} allowed, "
          f"{total - allowed} rejected/errored.")
    print(f"Client-side results written to {out_path}")
    print("Server-side metrics are in phase1-gateway/metrics/requests.csv")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Traffic simulator for the rate limiter benchmark")
    parser.add_argument("--pattern", choices=["steady", "burst", "ramp", "sustained_high"],
                         default="burst")
    parser.add_argument("--duration", type=int, default=60, help="seconds")
    parser.add_argument("--clients", type=int, default=5, help="number of distinct client IDs")
    parser.add_argument("--base-rate", type=int, default=5, help="baseline requests/sec")
    parser.add_argument("--burst-multiplier", type=int, default=6,
                         help="how many times base-rate the burst/high phase reaches")
    parser.add_argument("--out", type=str, default="results/run.csv")
    parser.add_argument("--seed", type=int, default=42,
                         help="random seed, so client-id selection is reproducible across runs")
    args = parser.parse_args()

    random.seed(args.seed)
    run(args.pattern, args.duration, args.clients, args.base_rate,
        args.burst_multiplier, args.out)

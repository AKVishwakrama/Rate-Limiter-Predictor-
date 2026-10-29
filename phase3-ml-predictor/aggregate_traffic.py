#!/usr/bin/env python3
"""
Turns the gateway's raw per-request metrics CSV (one row per request) into a
per-second request count CSV (one row per second) -- the shape the traffic
predictor model needs, since it forecasts "how many requests next second,"
not per-request behaviour.

Usage:
    python aggregate_traffic.py --input ../phase1-gateway/metrics/requests.csv \
        --output data/traffic_seconds.csv

Run this after each Phase 2 simulator run you want to use as training data.
You'll typically want several runs (different patterns: steady, burst, ramp)
concatenated together so the model sees varied traffic shapes, not just one.
"""

import argparse
import pandas as pd


def aggregate(input_path: str, output_path: str):
    df = pd.read_csv(input_path)
    df["second"] = df["timestamp_ms"] // 1000

    per_second = df.groupby("second").size().reset_index(name="request_count")
    # Fill gaps: if no requests landed in a given second, that's a real 0,
    # not a missing row -- the model needs to see actual lulls, not skip them.
    full_range = pd.DataFrame({
        "second": range(per_second["second"].min(), per_second["second"].max() + 1)
    })
    result = full_range.merge(per_second, on="second", how="left").fillna(0)
    result["request_count"] = result["request_count"].astype(int)

    result.to_csv(output_path, index=False)
    print(f"Aggregated {len(df)} requests into {len(result)} per-second rows.")
    print(f"Written to {output_path}")
    print(f"Traffic range: min={result['request_count'].min()}, "
          f"max={result['request_count'].max()}, "
          f"mean={result['request_count'].mean():.1f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="path to gateway metrics CSV")
    parser.add_argument("--output", required=True, help="path to write per-second CSV")
    args = parser.parse_args()
    aggregate(args.input, args.output)

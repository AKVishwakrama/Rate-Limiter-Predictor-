#!/usr/bin/env python3
"""
Generates a synthetic per-second traffic CSV in the same shape
aggregate_traffic.py produces, so you can train and sanity-check the
predictor before you've run real Phase 2 simulator traffic through the
gateway.

This is a bootstrap/testing convenience, NOT a substitute for training on
real traffic from your own simulator runs. Say so explicitly in your paper
if synthetic data is used for anything beyond initial pipeline testing --
claiming a model works based only on synthetic self-generated data is a
credibility problem a reviewer will flag immediately.

Usage:
    python generate_synthetic_traffic.py --duration 1800 --out data/synthetic_traffic.csv
"""

import argparse
import numpy as np
import pandas as pd


def generate(duration: int, seed: int) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    seconds = np.arange(duration)

    # Daily-cycle-like base wave + occasional random bursts, so the model
    # has both a learnable trend and unpredictable spikes to contend with.
    base = 8 + 4 * np.sin(seconds / 120.0)
    noise = rng.normal(0, 1.2, size=duration)
    counts = base + noise

    # Sprinkle in bursts: random start points, short duration, sharp spike.
    num_bursts = max(1, duration // 300)
    for _ in range(num_bursts):
        start = rng.integers(0, max(1, duration - 30))
        length = rng.integers(5, 20)
        magnitude = rng.uniform(3, 6)
        end = min(duration, start + length)
        counts[start:end] += counts[start:end] * magnitude

    counts = np.clip(counts, 0, None).round().astype(int)
    return pd.DataFrame({"second": seconds, "request_count": counts})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--duration", type=int, default=1800, help="seconds of data to generate")
    parser.add_argument("--out", type=str, default="data/synthetic_traffic.csv")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    df = generate(args.duration, args.seed)
    import os
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    df.to_csv(args.out, index=False)
    print(f"Wrote {len(df)} rows to {args.out}")
    print(f"Range: min={df['request_count'].min()}, max={df['request_count'].max()}, "
          f"mean={df['request_count'].mean():.1f}")

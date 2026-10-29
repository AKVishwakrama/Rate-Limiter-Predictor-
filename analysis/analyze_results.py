#!/usr/bin/env python3
"""
Reads the gateway's server-side metrics CSV (phase1-gateway/metrics/requests.csv)
for one or more experiment runs and produces:
  - summary table: p50/p95/p99 latency, drop rate, per strategy
  - a time-series plot of allowed vs rejected requests per second
  - a latency distribution comparison plot

Usage (compare two runs you've saved off after each experiment):
    python analyze_results.py \
        --runs token_bucket=results/metrics_token_bucket.csv \
               sliding_window=results/metrics_sliding_window.csv \
               ml_adaptive=results/metrics_ml_adaptive.csv \
        --out-dir results/analysis

Note: after each simulator.py run, copy phase1-gateway/metrics/requests.csv
to a named file (e.g. results/metrics_token_bucket.csv) BEFORE calling
/admin/metrics/reset for the next run -- otherwise you'll overwrite it.
"""

import argparse
import sys
from pathlib import Path

import pandas as pd
import matplotlib.pyplot as plt


def load_run(path: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    df["allowed"] = df["allowed"].astype(bool)
    return df


def summarize(name: str, df: pd.DataFrame) -> dict:
    total = len(df)
    allowed_df = df[df["allowed"]]
    rejected = total - len(allowed_df)

    latencies = allowed_df["latency_ms"] if "latency_ms" in df.columns else allowed_df.get("client_latency_ms")

    return {
        "strategy": name,
        "total_requests": total,
        "allowed": len(allowed_df),
        "rejected": rejected,
        "drop_rate_pct": round(100 * rejected / total, 2) if total else 0.0,
        "p50_latency_ms": round(latencies.quantile(0.50), 1) if len(latencies) else None,
        "p95_latency_ms": round(latencies.quantile(0.95), 1) if len(latencies) else None,
        "p99_latency_ms": round(latencies.quantile(0.99), 1) if len(latencies) else None,
    }


def plot_requests_per_second(runs: dict, out_dir: Path):
    fig, ax = plt.subplots(figsize=(10, 5))
    for name, df in runs.items():
        df = df.copy()
        df["second"] = (df["timestamp_ms"] - df["timestamp_ms"].min()) // 1000
        per_sec = df.groupby("second")["allowed"].agg(["sum", "count"])
        ax.plot(per_sec.index, per_sec["sum"], label=f"{name} (allowed)")
    ax.set_xlabel("Elapsed time (s)")
    ax.set_ylabel("Allowed requests / sec")
    ax.set_title("Allowed request rate over time, by strategy")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_dir / "requests_per_second.png", dpi=150)
    print(f"Saved {out_dir / 'requests_per_second.png'}")


def plot_latency_distribution(runs: dict, out_dir: Path):
    fig, ax = plt.subplots(figsize=(10, 5))
    for name, df in runs.items():
        col = "latency_ms" if "latency_ms" in df.columns else "client_latency_ms"
        allowed = df[df["allowed"]][col]
        ax.hist(allowed, bins=40, alpha=0.5, label=name)
    ax.set_xlabel("Latency (ms)")
    ax.set_ylabel("Request count")
    ax.set_title("Latency distribution of allowed requests, by strategy")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_dir / "latency_distribution.png", dpi=150)
    print(f"Saved {out_dir / 'latency_distribution.png'}")


def main():
    parser = argparse.ArgumentParser(description="Analyze rate limiter benchmark results")
    parser.add_argument("--runs", nargs="+", required=True,
                         help="name=path pairs, e.g. token_bucket=results/tb.csv")
    parser.add_argument("--out-dir", default="results/analysis")
    args = parser.parse_args()

    runs = {}
    for pair in args.runs:
        if "=" not in pair:
            print(f"Bad --runs entry (expected name=path): {pair}", file=sys.stderr)
            sys.exit(1)
        name, path = pair.split("=", 1)
        runs[name] = load_run(path)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    summary_rows = [summarize(name, df) for name, df in runs.items()]
    summary_df = pd.DataFrame(summary_rows)
    print("\n=== Summary ===")
    print(summary_df.to_string(index=False))

    summary_df.to_csv(out_dir / "summary.csv", index=False)
    print(f"\nSaved {out_dir / 'summary.csv'}")

    plot_requests_per_second(runs, out_dir)
    plot_latency_distribution(runs, out_dir)


if __name__ == "__main__":
    main()

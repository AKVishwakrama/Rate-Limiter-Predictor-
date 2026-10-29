#!/usr/bin/env python3
"""
Trains a model to predict next-second request count from the previous
N seconds of traffic (lag features).

Model choice: Linear Regression by default, Random Forest as an option.

Why linear regression as the default, not an LSTM or other deep model
(explain this choice in your paper -- it's a real, defensible decision,
not a shortcut):
  - The dataset here is small (thousands, not millions, of rows) -- deep
    models overfit small time-series data easily.
  - This model has to run inference inside a 1-second scheduling loop on
    the gateway side; a simple model keeps inference latency negligible.
  - Interpretability matters for a viva/paper defense: you can point at
    the learned coefficients and explain exactly why the model predicts
    what it predicts. You cannot do that with an LSTM.
  - It's a legitimate, honestly-disclosed baseline choice -- the paper's
    contribution is the ADAPTIVE THRESHOLD SYSTEM, not a novel forecasting
    model. Using a well-understood, appropriately-sized model for the
    forecasting sub-component is the correct engineering choice, not a
    weakness, as long as you state it explicitly instead of implying you
    used something fancier.

Usage:
    python train_model.py --input data/traffic_seconds.csv \
        --lags 10 --model linear --out model.joblib
"""

import argparse
import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import LinearRegression
from sklearn.metrics import mean_absolute_error, r2_score
from sklearn.model_selection import train_test_split


def build_lag_features(df: pd.DataFrame, lags: int) -> tuple[np.ndarray, np.ndarray]:
    counts = df["request_count"].values
    X, y = [], []
    for i in range(lags, len(counts)):
        X.append(counts[i - lags:i])
        y.append(counts[i])
    return np.array(X), np.array(y)


def train(input_path: str, lags: int, model_type: str, out_path: str):
    df = pd.read_csv(input_path)
    if len(df) < lags + 20:
        raise ValueError(
            f"Not enough data: {len(df)} rows, need at least {lags + 20} for a "
            f"meaningful train/test split. Run a longer simulator session or "
            f"generate more synthetic data."
        )

    X, y = build_lag_features(df, lags)

    # Time-series split: test set is the LAST chunk chronologically, not a
    # random shuffle. Shuffling would let the model "see the future" during
    # training via adjacent lag windows -- an easy, common mistake that
    # inflates reported accuracy. Explicitly avoided here.
    split_idx = int(len(X) * 0.8)
    X_train, X_test = X[:split_idx], X[split_idx:]
    y_train, y_test = y[:split_idx], y[split_idx:]

    if model_type == "linear":
        model = LinearRegression()
    elif model_type == "random_forest":
        model = RandomForestRegressor(n_estimators=100, max_depth=8, random_state=42)
    else:
        raise ValueError(f"Unknown model type: {model_type}")

    model.fit(X_train, y_train)

    preds = model.predict(X_test)
    mae = mean_absolute_error(y_test, preds)
    r2 = r2_score(y_test, preds)

    # Naive baseline: "predict next second = same as last second". A real
    # model needs to beat this or it's not adding value -- report both,
    # don't just report your model's numbers in isolation.
    naive_preds = X_test[:, -1]
    naive_mae = mean_absolute_error(y_test, naive_preds)

    print(f"Model: {model_type}, lags: {lags}")
    print(f"Train rows: {len(X_train)}, Test rows: {len(X_test)}")
    print(f"Model MAE: {mae:.2f}   |   Naive (last-value) MAE: {naive_mae:.2f}")
    print(f"Model R^2: {r2:.3f}")
    if mae >= naive_mae:
        print("WARNING: model does not beat the naive baseline. "
              "Report this honestly in the paper rather than hiding it -- "
              "it means either more/better training data is needed, or the "
              "lag window needs tuning.")

    joblib.dump({"model": model, "lags": lags, "model_type": model_type}, out_path)
    print(f"Saved model to {out_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="per-second traffic CSV")
    parser.add_argument("--lags", type=int, default=10,
                         help="how many previous seconds to use as features")
    parser.add_argument("--model", choices=["linear", "random_forest"], default="linear")
    parser.add_argument("--out", default="model.joblib")
    args = parser.parse_args()
    train(args.input, args.lags, args.model, args.out)

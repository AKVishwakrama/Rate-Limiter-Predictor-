#!/usr/bin/env python3
"""
Serves the trained traffic predictor model over HTTP so the Java gateway can
call it from AdaptiveThresholdScheduler once per second.

Run:
    uvicorn app:app --host 0.0.0.0 --port 8000

Endpoints:
    GET  /health              -> liveness check
    POST /predict              -> body: {"history": [int, int, ...]}  (last `lags` seconds)
                                   returns: {"predicted_next_second": float}

Why a separate Python service instead of embedding the model in the Java
gateway (e.g. via ONNX or PMML export): keeping the model in its native
scikit-learn form avoids a translation/export step that can silently change
numeric behaviour, and keeps model iteration (retraining, trying a new
model_type) entirely on the Python side without touching Java code. The
cost is one extra network hop per prediction -- acceptable since the
gateway only calls this once per second (see AdaptiveThresholdScheduler),
not once per request.
"""

import os
from pathlib import Path

import joblib
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

MODEL_PATH = os.environ.get("MODEL_PATH", "model.joblib")

app = FastAPI(title="Adaptive Rate Limiter - Traffic Predictor")

_bundle = None


def load_model():
    global _bundle
    path = Path(MODEL_PATH)
    if not path.exists():
        raise RuntimeError(
            f"No trained model found at {MODEL_PATH}. Run train_model.py first, "
            f"e.g.: python train_model.py --input data/traffic_seconds.csv --out model.joblib"
        )
    _bundle = joblib.load(path)
    print(f"Loaded model: {_bundle['model_type']}, lags={_bundle['lags']}")


@app.on_event("startup")
def startup():
    load_model()


class PredictRequest(BaseModel):
    history: list[int] = Field(..., description="Most recent request counts, oldest first")


class PredictResponse(BaseModel):
    predicted_next_second: float
    lags_used: int
    model_type: str


@app.get("/health")
def health():
    return {"status": "ok", "model_loaded": _bundle is not None}


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    if _bundle is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    lags = _bundle["lags"]
    model = _bundle["model"]

    if len(req.history) < lags:
        # Not enough history yet (e.g. gateway just started). Pad with the
        # earliest known value rather than failing outright -- this keeps
        # the adaptive threshold usable during the first few seconds after
        # startup instead of erroring, while being explicit about the
        # padding behaviour here rather than hiding it.
        pad_value = req.history[0] if req.history else 0
        padded = [pad_value] * (lags - len(req.history)) + req.history
        features = np.array(padded[-lags:]).reshape(1, -1)
    else:
        features = np.array(req.history[-lags:]).reshape(1, -1)

    prediction = float(model.predict(features)[0])
    prediction = max(0.0, prediction)  # request counts can't be negative

    return PredictResponse(
        predicted_next_second=round(prediction, 2),
        lags_used=lags,
        model_type=_bundle["model_type"],
    )

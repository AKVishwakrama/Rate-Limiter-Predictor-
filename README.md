# Adaptive Rate Limiting Using Machine Learning for Bursty Web Traffic

A comparative benchmark of rate limiting strategies for API gateways: token bucket
and sliding window (static baselines) vs. an ML-driven adaptive threshold, all running
inside one pluggable Spring Boot gateway so the comparison is fair. Includes a traffic
simulator, an analysis pipeline, and a live dashboard.

Read [DECISIONS.md](DECISIONS.md) for the reasoning behind every architectural
choice — that file is your interview/viva prep as much as this one is your setup guide.

---

## Project structure

```
adaptive-rate-limiter/
├── DECISIONS.md                   # why things are built this way
├── README.md                      # this file
├── phase1-gateway/                 # Spring Boot gateway + all 3 rate limiter strategies
│   └── src/main/java/com/ratelimiter/
│       ├── GatewayApplication.java
│       ├── config/                 # properties, ML-adaptive bean wiring, CORS
│       ├── limiter/                # RateLimiter interface + 3 implementations + registry
│       ├── predictor/              # TrafficRateTracker + MlPredictorClient (calls Phase 3)
│       ├── scheduler/              # AdaptiveThresholdScheduler (runs every 1s)
│       ├── controller/             # GatewayController (the endpoint) + AdminController
│       ├── metrics/                # CSV recorder + live in-memory stats for dashboard
│       └── model/
├── phase2-traffic-simulator/       # Python: generates steady/burst/ramp/sustained_high traffic
├── phase3-ml-predictor/            # Python: trains + serves the traffic forecasting model
│   ├── generate_synthetic_traffic.py
│   ├── aggregate_traffic.py
│   ├── train_model.py
│   └── app.py                      # FastAPI server the gateway calls
├── phase4-dashboard/               # React + Tailwind live dashboard
└── analysis/
    └── analyze_results.py          # p50/p95/p99 latency, drop rate, comparison plots
```

---

## Prerequisites

- JDK 17+, Maven 3.8+
- Python 3.10+, pip
- Node.js 18+, npm (for Phase 4 only)

---

## Run order

The four phases run as **separate processes**. Start them in this order:

```
Phase 3 predictor (port 8000)  ->  Phase 1 gateway (port 8080)  ->  Phase 4 dashboard (port 5173)
                                                ^
                                    Phase 2 simulator (fires requests at it)
```

The gateway works fine without Phase 3/4 running (token-bucket and sliding-window
don't need the predictor) — only switching to `ml-adaptive` strategy requires the
Phase 3 service to be up.

---

## Phase 1: Gateway

```bash
cd phase1-gateway
mvn spring-boot:run
```

Starts on `http://localhost:8080`.

```bash
curl "http://localhost:8080/api/resource?clientId=test1"
curl http://localhost:8080/admin/strategy
curl -X POST http://localhost:8080/admin/strategy -H "Content-Type: application/json" \
  -d '{"strategy":"sliding-window"}'
curl -X POST http://localhost:8080/admin/metrics/reset
```

**Note:** this Java project was written carefully against standard Spring Boot 3.2
patterns but has not been compiled in a live Maven environment during authoring (this
sandbox has no Maven Central access). Run `mvn spring-boot:run` early and treat any
compiler errors as normal first-run debugging, not a sign the whole design is wrong.

Tune `phase1-gateway/src/main/resources/application.yml` before running real
experiments — document whatever values you use in your paper.

---

## Phase 2: Traffic simulator

```bash
cd phase2-traffic-simulator
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt

python simulator.py --pattern burst --duration 60 --clients 5 \
    --base-rate 5 --burst-multiplier 6 --out results/burst_token_bucket.csv
```

Copy the gateway's `metrics/requests.csv` to a named file before resetting for the
next run:

```bash
cp ../phase1-gateway/metrics/requests.csv results/metrics_token_bucket.csv
curl -X POST http://localhost:8080/admin/metrics/reset
```

Patterns available: `steady`, `burst`, `ramp`, `sustained_high`. Use the same
`--pattern --duration --clients --base-rate --burst-multiplier --seed` across runs you
intend to compare, so the comparison is fair.

---

## Phase 3: ML traffic predictor

This is what makes `ml-adaptive` a real third strategy instead of a placeholder. Three
steps: get traffic data, train, serve.

```bash
cd phase3-ml-predictor
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
```

**Get training data.** Two options:

*Option A — use real traffic from your own Phase 2 runs (do this before finalizing
paper results):*
```bash
python aggregate_traffic.py --input ../phase1-gateway/metrics/requests.csv \
    --output data/traffic_seconds.csv
```

*Option B — bootstrap with synthetic data to test the pipeline immediately, before
you've collected real traffic:*
```bash
python generate_synthetic_traffic.py --duration 1800 --out data/synthetic_traffic.csv
```

**Train:**
```bash
python train_model.py --input data/traffic_seconds.csv --lags 10 --model linear \
    --out model.joblib
```

Read the console output carefully — it reports your model's MAE against a naive
"predict same as last second" baseline. If your model doesn't beat naive, that's a
real result to report honestly, not something to hide (see `train_model.py`'s
docstring for why this comparison matters).

**Serve:**
```bash
uvicorn app:app --host 0.0.0.0 --port 8000
```

```bash
curl http://localhost:8000/health
curl -X POST http://localhost:8000/predict -H "Content-Type: application/json" \
  -d '{"history":[5,6,7,8,9,10,11,12,13,14]}'
```

**Verification note:** this exact pipeline — synthetic data generation, training, and
serving a real prediction — was run end-to-end while building this project (using
`--duration 1800 --lags 10 --model linear`; result: model MAE 2.02 vs. naive baseline
MAE 2.03 on synthetic data — barely beating naive, which is an honest, reportable
result, not a failure to hide). It works exactly as documented. What has NOT been
tested is the Java gateway actually calling it live end-to-end (no Maven environment
available in this sandbox) — test that integration yourself as your first real run.

With the predictor running, switch the gateway to use it:
```bash
curl -X POST http://localhost:8080/admin/strategy -H "Content-Type: application/json" \
  -d '{"strategy":"ml-adaptive"}'
```

Predictions and the threshold applied each second are logged to
`phase1-gateway/metrics/predictions.csv` — use this to evaluate the model's own
accuracy (predicted vs. actual traffic) as a separate results table from your
end-to-end latency/drop-rate comparison.

---

## Phase 4: Live dashboard

```bash
cd phase4-dashboard
npm install
npm run dev
```

Opens on `http://localhost:5173`. Requires the gateway (Phase 1) running on port 8080.
Lets you switch strategies with a click, watch allowed/rejected requests per second
live, and see the ML-adaptive threshold move in real time. Good for demos and
screenshots — **not** a substitute for `analyze_results.py`'s CSV-based analysis when
it comes to the actual numbers you report in your paper.

**Not yet run/verified** in this build (no npm/node available in the authoring
sandbox) — same caveat as the Java side. Run `npm install && npm run dev` yourself
first and debug from there if anything's off.

---

## Step 5: Analyze results

```bash
cd analysis
python analyze_results.py --runs \
    token_bucket=../phase2-traffic-simulator/results/metrics_token_bucket.csv \
    sliding_window=../phase2-traffic-simulator/results/metrics_sliding_window.csv \
    ml_adaptive=../phase2-traffic-simulator/results/metrics_ml_adaptive.csv \
    --out-dir results
```

Produces `summary.csv`, `requests_per_second.png`, `latency_distribution.png` — these
go directly into your paper's results section.

---

## Roadmap

- [x] **Phase 1** — Gateway with pluggable token bucket / sliding window / ml-adaptive
      strategies, runtime-switchable, CSV metrics logging
- [x] **Phase 2** — Traffic simulator with configurable burst patterns, analysis
      script producing latency percentiles and comparison plots
- [x] **Phase 3** — ML traffic predictor (scikit-learn, FastAPI), integrated as a
      third gateway strategy via a scheduled threshold update — pipeline verified
      end-to-end during authoring
- [x] **Phase 4** — React + Tailwind live dashboard for strategy switching and
      real-time traffic visualization
- [ ] **Future work** — Postgres-backed metrics, learned (not hand-tuned) threshold
      policy, automated model retraining, AWS + Nginx deployment of the full stack

See [DECISIONS.md](DECISIONS.md) section 13 for the full list of what's deliberately
out of scope and why.

---

## For the paper

Suggested title (avoids generic/overloaded words like "System"):
**"Adaptive Rate Limiting Using Machine Learning for Bursty Web Traffic"**

Suggested methodology section structure:
1. Baseline algorithms (token bucket, sliding window) — implementation details from
   this repo, config values used
2. Traffic patterns tested — steady, burst, ramp, sustained_high, with parameters
3. ML predictor — model type (linear regression vs. random forest — report both if you
   tried both), lag window, training data source and size, MAE vs. naive baseline
4. Adaptive threshold formula — the exact proportional-scaling logic from
   `AdaptiveThresholdScheduler`, and the constants used
5. Metrics — p50/p95/p99 latency, drop rate, recovery time after burst, for all three
   strategies under identical traffic
6. Results — comparison table + plots from `analyze_results.py`, plus a separate
   predicted-vs-actual accuracy table from `predictions.csv`
7. Limitations — see DECISIONS.md: simulated backend, CSV metrics ceiling, hand-tuned
   (not learned) threshold policy, single-machine benchmark, predictor-outage fallback
   behaviour

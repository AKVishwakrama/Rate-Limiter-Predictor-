# Adaptive Rate Limiting Using Machine Learning for Bursty Web Traffic

A multi-strategy API rate-limiting project comparing **Token Bucket**, **Sliding Window**, and **ML Adaptive** rate limiting under different traffic patterns.

The project includes a Spring Boot API gateway, Python traffic simulator, FastAPI traffic predictor, and React dashboard. The gateway supports runtime strategy switching, request metrics, and scheduled ML-based threshold updates.

## Features

- Three runtime-selectable strategies:
    - **Token Bucket:** token replenishment-based rate limiting.
    - **Sliding Window:** request counting over a time window.
    - **ML Adaptive:** predicts near-future traffic and adjusts the per-client threshold.
- Traffic simulation with steady, burst, ramp, and sustained-high patterns.
- FastAPI prediction service using a trained scikit-learn model.
- Scheduled predictions instead of making an ML call for every incoming request.
- Live dashboard showing allowed/rejected requests, active strategy, system health, prediction, and applied threshold.
- CSV metrics and an analysis script for benchmark experiments.

## Architecture

```text
Python Traffic Simulator
          |
          v
Spring Boot API Gateway <----> React Dashboard
          |
          | Scheduled prediction request
          v
FastAPI ML Predictor
```

**Ports**
- Gateway: `8080`
- ML Predictor: `8000`
- Dashboard: `5173`

## Project Structure

```text
adaptive-rate-limiter/
├── DECISIONS.md
├── README.md
├── phase1-gateway/             # Spring Boot gateway and rate limiters
│   ├── src/main/java/com/ratelimiter/
│   └── metrics/                # Generated request/prediction metrics
├── phase2-traffic-simulator/   # Python traffic generator
│   ├── simulator.py
│   └── results/
├── phase3-ml-predictor/        # Model training and FastAPI service
│   ├── app.py
│   ├── train_model.py
│   ├── generate_synthetic_traffic.py
│   └── aggregate_traffic.py
├── phase4-dashboard/           # React + Vite dashboard
└── analysis/
    └── analyze_results.py
```

## Requirements

- Java 17+
- Maven 3.8+
- Python 3.10+
- Node.js and npm
- Trained model file: `phase3-ml-predictor/model.joblib`

## Running the Project on Windows

Run each service in a separate PowerShell terminal.

### 1. Start the ML Predictor

```powershell
cd "phase3-ml-predictor"
python -m pip install -r requirements.txt
python -m uvicorn app:app --host 127.0.0.1 --port 8000
```

Check predictor health:

```powershell
Invoke-RestMethod "http://127.0.0.1:8000/health"
```

Test a prediction:

```powershell
$body = '{"history":[0,0,0,0,0,0,0,0,0,0]}'
Invoke-RestMethod -Uri "http://127.0.0.1:8000/predict" -Method Post -ContentType "application/json" -Body $body
```

### 2. Start the Spring Boot Gateway

```powershell
cd "phase1-gateway"
mvn spring-boot:run
```

Gateway URL: `http://localhost:8080`

Useful endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /api/resource?clientId=test1` | Send a sample request |
| `GET /admin/strategy` | Read active strategy |
| `POST /admin/strategy` | Change active strategy |
| `GET /admin/metrics/live` | Read live metrics |
| `POST /admin/metrics/reset` | Reset metrics |

Switch strategy:

```powershell
$body = '{"strategy":"token-bucket"}'
Invoke-RestMethod -Uri "http://localhost:8080/admin/strategy" -Method Post -ContentType "application/json" -Body $body
```

Other supported values:

- `sliding-window`
- `ml-adaptive`

Read live metrics:

```powershell
Invoke-RestMethod "http://localhost:8080/admin/metrics/live" | ConvertTo-Json -Depth 5
```

### 3. Start the Dashboard

```powershell
cd "phase4-dashboard"
npm install
npm run dev
```

Open `http://localhost:5173`.

The dashboard supports strategy switching, metrics reset, live allowed/rejected counters, system health, and ML prediction monitoring.

### 4. Start the Traffic Simulator

```powershell
cd "phase2-traffic-simulator"
python simulator.py --help
```

Supported traffic patterns:

- `steady`
- `burst`
- `ramp`
- `sustained_high`

Example: normal traffic

```powershell
python simulator.py --pattern steady --duration 20 --clients 5 --base-rate 2 --out results/steady-token.csv --seed 42
```

Example: burst traffic

```powershell
python simulator.py --pattern burst --duration 30 --clients 5 --base-rate 30 --burst-multiplier 6 --out results/burst-token.csv --seed 42
```

## Comparing the Three Strategies

For a fair comparison:

1. Select one strategy in the dashboard.
2. Reset metrics before each experiment.
3. Run the simulator with the same traffic parameters.
4. Record allowed and rejected requests.
5. Repeat with the other strategies.
6. Compare the measured results.

Suggested experiment matrix:

| Scenario | Pattern | Example settings |
|---|---|---|
| Normal traffic | `steady` | 20 sec, 5 clients, rate 2 |
| Burst traffic | `burst` | 30 sec, 5 clients, rate 30, multiplier 6 |
| Increasing traffic | `ramp` | Keep parameters identical across strategies |
| Sustained traffic | `sustained_high` | Keep parameters identical across strategies |

Actual rejection counts depend on the limiter configuration and generated traffic. Record measured values rather than assuming expected counts.

## How ML Adaptive Works

1. The gateway tracks recent request traffic.
2. The adaptive scheduler runs periodically (currently every second).
3. It sends recent traffic history to the Python `/predict` endpoint.
4. The predictor returns `predicted_next_second`, `lags_used`, and `model_type`.
5. The gateway uses the prediction to update the adaptive per-client threshold.

The model is called by the scheduled control loop, not once per incoming request. A prediction can be received without changing the threshold if the predicted traffic does not require an adjustment.

## Training the Predictor

To regenerate a model, use the scripts in `phase3-ml-predictor`. For example:

```powershell
cd "phase3-ml-predictor"
python generate_synthetic_traffic.py --duration 1800 --out data/synthetic_traffic.csv
python train_model.py --input data/synthetic_traffic.csv --lags 10 --model linear --out model.joblib
```

Check each script's `--help` for its supported options. Ensure `model.joblib` is available at the path configured for the predictor.

For research reporting, document the training dataset, model, lag count, prediction error, and baseline comparison. Do not present synthetic-data performance as real-world production performance.

## Metrics and Analysis

Gateway metrics are recorded under:

```text
phase1-gateway/metrics/
```

Simulator outputs are stored under:

```text
phase2-traffic-simulator/results/
```

Check the analysis script's options:

```powershell
cd "analysis"
python analyze_results.py --help
```

Use identical workloads across strategies and compare measured request counts and latency percentiles.

## Troubleshooting

- **Predictor unavailable:** Check that port 8000 is running and `/health` reports a loaded model.
- **Prediction unavailable:** Check gateway logs, predictor URL, and Python service status.
- **No rejected requests:** Check the active strategy, traffic rate, client count, and limiter configuration.
- **Dashboard not updating:** Ensure the gateway is running on port 8080.
- **Port already in use:** Identify the process using the port before stopping it.

## Scope and Limitations

- This is a local experimental benchmark/demo, not a production-hardened distributed rate limiter.
- Traffic is generated using a simulator.
- The ML model predicts traffic; the gateway's limiter makes the allow/reject decision.
- The adaptive threshold policy is implemented in the gateway.
- CSV metrics are intended for experiments and can grow over time.
- Report only benchmark measurements collected from actual runs.

## Further Reading

See [DECISIONS.md](DECISIONS.md) for architectural decisions and implementation trade-offs.

# Decisions Log

This file explains *why* the project is built the way it is. In interviews and in the
paper, "what did you build" matters less than "why did you build it this way, and what
did you trade off." Every major decision below follows that format: the decision, the
alternative considered, and why the alternative was rejected for this phase.

---

## 1. Unified pluggable gateway instead of three separate services

**Decision:** One Spring Boot process holds all rate limiting strategies and switches
between them at runtime via `POST /admin/strategy`.

**Alternative considered:** Deploy token-bucket, sliding-window, and (later) ML-adaptive
as three separate services, benchmark each independently.

**Why rejected:** Separate services introduce confounding variables into the comparison
— different JVM warm-up states, potentially different machines/containers, different
background GC pauses. If the goal is to isolate "does the algorithm change the outcome,"
everything else about the environment needs to stay identical between runs. A single
process with a runtime-switchable strategy holds the JVM, the OS, the CPU, and the
downstream simulated backend constant, so only the rate limiting logic differs between
experiment runs.

**Trade-off accepted:** This is a less "microservice-shaped" design. That's fine — this
project isn't demonstrating microservice architecture, it's demonstrating rate limiting
algorithm behaviour. Optimizing for the wrong property would hurt the actual research
question.

---

## 2. Rate limiting algorithms implemented from scratch, not via a library

**Decision:** `TokenBucketRateLimiter` and `SlidingWindowRateLimiter` are hand-written,
not pulled from Resilience4j, Bucket4j, or Guava's RateLimiter.

**Why:** The entire value of this project — both for the paper and for interview
discussions — is being able to explain exactly how the algorithm behaves under
concurrency, why a given data structure was chosen, and what its failure modes are. A
library call proves nothing about understanding. This also means the two baselines are
comparable on equal terms (same language, same concurrency primitives), which matters
for a fair benchmark.

**Trade-off accepted:** More code to maintain and more surface area for bugs than a
battle-tested library would have. Mitigated by keeping each implementation small and
single-purpose (see the per-class Javadoc for the concurrency reasoning in each).

---

## 3. CSV metrics instead of PostgreSQL for Phase 1

**Decision:** Every request outcome is appended to `metrics/requests.csv` via a
synchronized writer, not written to Postgres.

**Why:** A grader, an interviewer, or you six months from now should be able to clone
this repo, run one `mvn spring-boot:run` command, and get a working benchmark with zero
extra infrastructure. Requiring a running Postgres instance for something as simple as
"log a row per request" adds setup friction that doesn't buy anything at this phase's
data volumes (tens of thousands of rows per experiment run, easily handled by a flat
file).

**Known limitation (disclose this in the paper, don't hide it):** the synchronized
file write is a throughput ceiling — every request thread briefly blocks on file I/O.
At very high request rates this could itself distort latency measurements. Acceptable
for Phase 1/2 scope; the documented path forward is an async queue + background writer
thread, or moving to Postgres with batched inserts, in Phase 3.

---

## 4. Simulated in-process backend work instead of a real downstream proxy hop

**Decision:** `GatewayController` doesn't proxy to a separate backend service. It
directly simulates processing with a random 50–150ms sleep.

**Why:** A real network hop to a downstream service introduces its own latency
variance (DNS, connection pooling, downstream GC pauses, downstream load). That
variance would mix with the rate limiter's own behaviour in the latency numbers,
making it harder to attribute a latency change specifically to the rate limiting
strategy. Isolating the variable under test is the point.

**Trade-off accepted:** This is less realistic than a true gateway-to-service
topology. If asked in an interview "would this hold up in production," the honest
answer is: this phase is intentionally a controlled benchmark environment, not a
production topology — and you should say that proactively, not wait to be asked.

---

## 5. Per-client rate limiting (not global)

**Decision:** Every limiter is keyed by `clientId`, so client A being throttled
doesn't affect client B.

**Why:** This matches how real rate limiting is used in production (per API key, per
user, per tenant) and is also what makes the traffic simulator's multi-client
scenarios meaningful — a burst from one client shouldn't silently punish others
sharing the gateway. It also makes the concurrency story more interesting to discuss:
per-client locking (see `TokenBucketRateLimiter`'s per-bucket `synchronized`) means
only requests from the *same* client contend with each other, not all requests
globally.

---

## 6. Token bucket vs sliding window: why compare these two specifically

**Decision:** These are the two static baselines, before the ML-adaptive layer is
added in Phase 3.

**Why these two and not others (e.g. fixed window counter, leaky bucket):** token
bucket and sliding window represent two genuinely different philosophies — token
bucket *allows* short bursts up to a capacity, sliding window enforces a hard cap over
any rolling interval with no burst allowance. That difference is exactly what should
produce visibly different behaviour under the "burst" and "ramp" traffic patterns in
Phase 2, which is the whole point of running a comparison instead of picking one
algorithm and calling it done.

---

## 7. Python for the traffic simulator, not k6/Locust/JMeter

**Decision:** `simulator.py` is a plain Python script using `requests` +
`ThreadPoolExecutor`.

**Why:** Off-the-shelf load testing tools are built for steady or ramping load, not
for the specific, precisely-shaped burst patterns this experiment needs (a short sharp
spike in the middle of an otherwise steady run, for instance). Writing the pattern
generator directly means full control over the exact shape of each traffic scenario,
and it keeps the whole pipeline (simulate → log → analyze) in one language, feeding
directly into `analyze_results.py`'s pandas-based analysis without a format conversion
step.

**Trade-off accepted:** A hand-rolled simulator is less battle-tested than k6, and
won't scale to the request volumes a real load testing tool could generate. Not a
concern at this phase's scale (tens of requests/sec).

---

## 8. ML predictor as a separate Python service, not embedded in the Java gateway

**Decision:** The traffic predictor (`phase3-ml-predictor/`) runs as its own FastAPI
process. The gateway calls it over HTTP once per second, not per request.

**Alternative considered:** Export the trained scikit-learn model to ONNX or PMML and
run inference directly inside the Java process.

**Why rejected:** An export step is a translation layer that can silently change
numeric behaviour between what you trained/tested in Python and what actually runs in
production — a real, documented source of subtle bugs in ML systems. Keeping the model
in its native scikit-learn form means what you evaluated in `train_model.py` is
exactly what serves predictions in `app.py`. The cost is one extra network hop, but
since the gateway only needs a fresh prediction once per second (not once per request
— see decision 9), that hop is negligible relative to the 1-second scheduling interval.

**Trade-off accepted:** Two processes to run instead of one. Documented clearly in the
README's run order.

---

## 9. Predicting once per second, not once per request

**Decision:** `AdaptiveThresholdScheduler` calls the predictor on a fixed 1-second
schedule and updates a shared threshold. Individual requests never trigger a
prediction call themselves — `MlAdaptiveRateLimiter.tryAcquire()` just reads whatever
threshold was last set.

**Why:** Calling the predictor synchronously inside the request path would add
network-call latency to every single request, which would then show up as "the ML
strategy is just slower" in your latency comparison — for a reason that has nothing to
do with whether the adaptive threshold logic is actually good. Decoupling prediction
frequency from request frequency keeps the request path's latency comparable across
all three strategies, so the comparison measures what it's supposed to measure.

---

## 10. Threshold formula: proportional scaling against a target capacity, not a learned policy

**Decision:** `AdaptiveThresholdScheduler.computeThreshold()` uses a simple ratio
(`targetCapacity / predictedLoad`) to scale the per-client threshold between a
configured min and max, rather than training a second model to learn the optimal
threshold-setting policy directly.

**Why:** Keeping the policy layer simple and hand-specified means you can explain and
defend exactly why the threshold moved the way it did at any point in an experiment —
critical for a viva or interview walkthrough. Learning the policy itself would be a
legitimate but substantially larger research project (this edges toward reinforcement
learning territory) and isn't necessary to answer the actual research question: does
*any* reasonable ML-informed adaptive threshold beat a static one under bursty traffic.

**Honest limitation to name in the paper:** the constants (`target-global-capacity-per-sec`,
`min-threshold`, `max-threshold`) are hand-tuned, not learned or optimized. State this
plainly, and ideally report a small sensitivity analysis (how results change if you
tune target capacity by ±20%) rather than presenting one untuned run as definitive.

---

## 11. Predictor outage fallback: hold last threshold, don't guess

**Decision:** If `MlPredictorClient` can't reach the Python service, the scheduler
keeps the last successfully computed threshold rather than falling back to a default
or failing the request path.

**Why:** A rate limiter is infrastructure — it should degrade gracefully, not become a
new point of failure. Silently reverting to "static at last value" during a predictor
outage is honest, safe behaviour. Worth stating explicitly in the paper: this means
the "adaptive" limiter isn't adaptive 100% of the time by design, and that's a
deliberate reliability choice, not an oversight.

---

## 12. Dashboard reads live in-memory stats, not the growing CSV file

**Decision:** `LiveStatsService` keeps a small in-memory rolling window (last 30
seconds) of allowed/rejected counts. The Phase 4 dashboard polls `/admin/metrics/live`,
which reads this in-memory state — it never re-reads `requests.csv` on each poll.

**Why:** Re-parsing a CSV file that grows for the whole duration of an experiment
would make each dashboard poll progressively slower as the run goes on, and that
slowdown itself could distort what you're trying to observe. An in-memory ring-buffer-style
counter avoids that entirely and costs almost nothing per request.

---

## 13. What's explicitly NOT in this delivery

- Postgres-backed metrics storage (CSV is used throughout — see decision 3)
- Real downstream service topology (backend work is simulated in-process — see decision 4)
- A learned threshold-setting policy (see decision 10 — the policy is a simple, explainable formula)
- AWS/Nginx deployment scripts for the dashboard (README documents manual deploy steps
  consistent with the AWS + Nginx setup already used in a prior project, but no IaC/CI
  is included here)
- Automated retraining of the predictor model on new traffic (retraining is a manual
  step: re-run `aggregate_traffic.py` + `train_model.py` and restart the FastAPI service)

Listing this here isn't a formality — in the paper's "future work" section and in an
interview, being able to say precisely what's in scope and what's deliberately
deferred (and why) is itself a signal of engineering judgment.

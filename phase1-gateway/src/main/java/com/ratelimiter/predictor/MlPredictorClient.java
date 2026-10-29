package com.ratelimiter.predictor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client to the Phase 3 Python prediction service.
 *
 * Failure handling: if the predictor service is unreachable or errors out,
 * this returns empty (Optional) rather than throwing. AdaptiveThresholdScheduler
 * treats that as "keep the last known threshold" -- a prediction-service
 * outage should degrade the adaptive limiter to its last good setting, not
 * take down the gateway's request path. Disclose this fallback behaviour
 * explicitly in the paper's limitations section: it means the "adaptive"
 * limiter silently becomes "static at last value" during a predictor
 * outage, which is a real, worth-naming failure mode.
 */
@Component
public class MlPredictorClient {

    private final RestClient restClient;

    public MlPredictorClient(@Value("${ml-adaptive.predictor-url:http://localhost:8000}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public record PredictionResult(double predictedNextSecond, String modelType) {}

    public java.util.Optional<PredictionResult> predict(List<Integer> history) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/predict")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of("history", history))
                    .retrieve()
                    .body(Map.class);

            if (response == null) return java.util.Optional.empty();

            double predicted = ((Number) response.get("predicted_next_second")).doubleValue();
            String modelType = String.valueOf(response.get("model_type"));
            return java.util.Optional.of(new PredictionResult(predicted, modelType));
        } catch (RestClientException e) {
            System.err.println("Predictor service call failed, keeping last threshold: " + e.getMessage());
            return java.util.Optional.empty();
        }
    }
}

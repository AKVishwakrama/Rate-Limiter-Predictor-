//package com.ratelimiter.predictor;
//
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//import org.springframework.web.client.RestClient;
//import org.springframework.web.client.RestClientException;
//
//import java.util.List;
//import java.util.Map;
//
///**
// * Thin HTTP client to the Phase 3 Python prediction service.
// *
// * Failure handling: if the predictor service is unreachable or errors out,
// * this returns empty (Optional) rather than throwing. AdaptiveThresholdScheduler
// * treats that as "keep the last known threshold" -- a prediction-service
// * outage should degrade the adaptive limiter to its last good setting, not
// * take down the gateway's request path. Disclose this fallback behaviour
// * explicitly in the paper's limitations section: it means the "adaptive"
// * limiter silently becomes "static at last value" during a predictor
// * outage, which is a real, worth-naming failure mode.
// */
//@Component
//public class MlPredictorClient {
//
//    private final RestClient restClient;
//
//    public MlPredictorClient(@Value("${ml-adaptive.predictor-url:http://localhost:8000}") String baseUrl) {
//        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
//    }
//
//    public record PredictionResult(double predictedNextSecond, String modelType) {}
//
//    public java.util.Optional<PredictionResult> predict(List<Integer> history) {
//        try {
//            Map<String, Object> response = restClient.post()
//                    .uri("/predict")
//                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
//                    .body(Map.of("history", history))
//                    .retrieve()
//                    .body(Map.class);
//
//            if (response == null) return java.util.Optional.empty();
//
//            double predicted = ((Number) response.get("predicted_next_second")).doubleValue();
//            String modelType = String.valueOf(response.get("model_type"));
//            return java.util.Optional.of(new PredictionResult(predicted, modelType));
//        } catch (RestClientException e) {
//            System.err.println("Predictor service call failed, keeping last threshold: " + e.getMessage());
//            return java.util.Optional.empty();
//        }
//    }
//}




//
//package com.ratelimiter.predictor;
//
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.net.http.HttpRequest;
//import java.net.http.HttpResponse;
//import java.time.Duration;
//import java.util.List;
//import java.util.Optional;
//import java.util.stream.Collectors;
//
//@Component
//public class MlPredictorClient {
//
//    private final HttpClient httpClient;
//    private final String predictorUrl;
//
//    public MlPredictorClient(
//            @Value("${ml-adaptive.predictor-url:http://127.0.0.1:8000}")
//            String baseUrl) {
//
////        this.predictorUrl = baseUrl + "/predict";
//        this.predictorUrl = baseUrl + "/debug/echo";
//
//        this.httpClient = HttpClient.newBuilder()
//                .connectTimeout(Duration.ofSeconds(3))
//                .build();
//    }
//
//    public record PredictionResult(
//            double predictedNextSecond,
//            String modelType
//    ) {}
//
//    public Optional<PredictionResult> predict(List<Integer> history) {
//
//        try {
//            String values = history.stream()
//                    .map(String::valueOf)
//                    .collect(Collectors.joining(","));
//
//            String json = "{\"history\":[" + values + "]}";
//
//            System.out.println("Java HTTP URL: " + predictorUrl);
//            System.out.println("Java HTTP JSON: " + json);
//
//            HttpRequest request = HttpRequest.newBuilder()
//                    .uri(URI.create(predictorUrl))
//                    .timeout(Duration.ofSeconds(5))
//                    .header("Content-Type", "application/json")
//                    .header("Accept", "application/json")
//                    .POST(HttpRequest.BodyPublishers.ofString(json))
//                    .build();
//
//            HttpResponse<String> response = httpClient.send(
//                    request,
//                    HttpResponse.BodyHandlers.ofString()
//            );
//
//            System.out.println("Predictor HTTP status: " +
//                    response.statusCode());
//
//            System.out.println("Predictor raw response: " +
//                    response.body());
//
//            if (response.statusCode() != 200) {
//                return Optional.empty();
//            }
//
//            String body = response.body();
//
//            String predictedText = body.replaceAll(
//                    ".*\"predicted_next_second\"\\s*:\\s*([-+0-9.eE]+).*",
//                    "$1"
//            );
//
//            String modelType = body.replaceAll(
//                    ".*\"model_type\"\\s*:\\s*\"([^\"]+)\".*",
//                    "$1"
//            );
//
//            if (predictedText.equals(body)) {
//                System.err.println("Could not parse prediction: " + body);
//                return Optional.empty();
//            }
//
//            double predicted = Double.parseDouble(predictedText);
//
//            return Optional.of(
//                    new PredictionResult(predicted, modelType)
//            );
//
//        } catch (Exception e) {
//            System.err.println("Java HTTP predictor error: " +
//                    e.getMessage());
//            e.printStackTrace();
//            return Optional.empty();
//        }
//    }
//}




package com.ratelimiter.predictor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class MlPredictorClient {

    private final String predictorUrl;

    public MlPredictorClient(
            @Value("${ml-adaptive.predictor-url:http://127.0.0.1:8000}")
            String baseUrl) {

        this.predictorUrl = baseUrl + "/predict";
    }

    public record PredictionResult(
            double predictedNextSecond,
            String modelType
    ) {}

    public Optional<PredictionResult> predict(List<Integer> history) {

        HttpURLConnection connection = null;

        try {
            String values = history.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            String json = "{\"history\":[" + values + "]}";
            byte[] requestBytes = json.getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection)
                    new URL(predictorUrl).openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestProperty(
                    "Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");

            connection.setFixedLengthStreamingMode(requestBytes.length);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
                output.flush();
            }

            int status = connection.getResponseCode();

            InputStream input = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            String responseBody;

            try (ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                if (input != null) {
                    try (input) {
                        input.transferTo(result);
                    }
                }
                responseBody = result.toString(StandardCharsets.UTF_8);
            }

            System.out.println("Predictor HTTP status: " + status);
            System.out.println("Predictor raw response: " + responseBody);

            if (status != 200) {
                return Optional.empty();
            }

            String predictedText = responseBody.replaceAll(
                    ".*\"predicted_next_second\"\\s*:\\s*([-+0-9.eE]+).*",
                    "$1"
            );

            String modelType = responseBody.replaceAll(
                    ".*\"model_type\"\\s*:\\s*\"([^\"]+)\".*",
                    "$1"
            );

            if (predictedText.equals(responseBody)) {
                System.err.println("Could not parse prediction: " + responseBody);
                return Optional.empty();
            }

            double predicted = Double.parseDouble(predictedText);

            return Optional.of(
                    new PredictionResult(predicted, modelType)
            );

        } catch (Exception e) {
            System.err.println("Predictor connection error: " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}

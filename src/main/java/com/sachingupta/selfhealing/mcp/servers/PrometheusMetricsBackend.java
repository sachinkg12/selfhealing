package com.sachingupta.selfhealing.mcp.servers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real {@link MetricsBackend} that queries a Prometheus HTTP API. Translates the MCP-level
 * arguments into PromQL and the response back into the same Map shape the fake produces, so
 * downstream consumers (advisors, scenarios, the LLM planner) are oblivious to the backend choice.
 *
 * <p>Active when {@code selfhealing.observability.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "selfhealing.observability.enabled", havingValue = "true")
public class PrometheusMetricsBackend implements MetricsBackend {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String serviceDeploymentSuffix;
    private final HttpClient http;

    public PrometheusMetricsBackend(
            @Value("${selfhealing.prometheus.url:http://localhost:9090}") String baseUrl,
            @Value("${selfhealing.k8s.scope-deployment-suffix:-api}") String suffix) {
        this.baseUrl = baseUrl;
        this.serviceDeploymentSuffix = suffix;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        log.info("PrometheusMetricsBackend ready (baseUrl={})", baseUrl);
    }

    @Override
    public Map<String, Object> rangeQuery(String scope, Map<String, Object> arguments) {
        String service = scope + serviceDeploymentSuffix;
        String query = p99Query(service);
        long end = Instant.now().getEpochSecond();
        long start = end - 600; // last 10 minutes
        JsonNode result =
                fetch(
                        "/api/v1/query_range?query="
                                + url(query)
                                + "&start="
                                + start
                                + "&end="
                                + end
                                + "&step=15s");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", "http_request_duration_p99_ms");
        out.put("service", scope);
        out.put("query", query);
        SeriesSummary summary = summarize(result);
        out.put("series", summary.series);
        out.put("shape", summary.shape);
        out.put("signal", summary.signal);
        return out;
    }

    @Override
    public Map<String, Object> instantQuery(String scope, Map<String, Object> arguments) {
        String service = scope + serviceDeploymentSuffix;
        String query = p99Query(service);
        long now = Instant.now().getEpochSecond();
        JsonNode result = fetch("/api/v1/query?query=" + url(query) + "&time=" + now);
        double valueMs = currentValueMs(result);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", "http_request_duration_p99_ms");
        out.put("service", scope);
        out.put("query", query);
        out.put("value", Math.round(valueMs));
        out.put(
                "signal",
                valueMs > 800
                        ? "p99 elevated (" + Math.round(valueMs) + "ms)"
                        : "p99 back to baseline (" + Math.round(valueMs) + "ms)");
        return out;
    }

    @Override
    public Map<String, Object> alertState(String scope) {
        // No Alertmanager wired in the demo; report from Prometheus rules endpoint if present.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", scope);
        out.put("alerts", List.of());
        out.put("signal", "alertmanager not configured");
        return out;
    }

    private static String p99Query(String service) {
        // 1000 * histogram_quantile so the return value is in ms (matches the fake's units).
        return "1000 * histogram_quantile(0.99, sum by(le) "
                + "(rate(http_request_duration_seconds_bucket{service=\""
                + service
                + "\"}[1m])))";
    }

    private JsonNode fetch(String path) {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MetricsBackendException(
                        "Prometheus HTTP " + response.statusCode() + ": " + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (MetricsBackendException e) {
            throw e;
        } catch (Exception e) {
            throw new MetricsBackendException(
                    "Prometheus query failed: "
                            + e.getClass().getSimpleName()
                            + ": "
                            + e.getMessage());
        }
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record SeriesSummary(String series, String shape, String signal) {}

    private static SeriesSummary summarize(JsonNode root) {
        JsonNode results = root.path("data").path("result");
        if (!results.isArray() || results.isEmpty()) {
            return new SeriesSummary("no data", "flat", "p99 no samples yet");
        }
        JsonNode values = results.get(0).path("values");
        if (!values.isArray() || values.isEmpty()) {
            return new SeriesSummary("no data", "flat", "p99 no samples yet");
        }
        double first = values.get(0).get(1).asDouble();
        double last = values.get(values.size() - 1).get(1).asDouble();
        double max = first;
        double min = first;
        for (JsonNode v : values) {
            double d = v.get(1).asDouble();
            if (d > max) max = d;
            if (d < min) min = d;
        }
        String series =
                String.format(
                        "%d samples, first %.0fms / last %.0fms / max %.0fms",
                        values.size(), first, last, max);
        boolean spiking = max > 800 && last > 800;
        String shape =
                spiking ? "step at recent T" : (last < 400 && max > 800 ? "recovering" : "flat");
        String signal;
        if (spiking) {
            signal = "p99 elevated (last " + Math.round(last) + "ms)";
        } else if (last < 400 && max > 800) {
            signal = "p99 back to baseline after spike";
        } else {
            signal = "p99 nominal";
        }
        return new SeriesSummary(series, shape, signal);
    }

    private static double currentValueMs(JsonNode root) {
        JsonNode results = root.path("data").path("result");
        if (!results.isArray() || results.isEmpty()) {
            return 0.0;
        }
        JsonNode value = results.get(0).path("value");
        if (!value.isArray() || value.size() < 2) {
            return 0.0;
        }
        return value.get(1).asDouble();
    }

    public static class MetricsBackendException extends RuntimeException {
        public MetricsBackendException(String message) {
            super(message);
        }
    }
}

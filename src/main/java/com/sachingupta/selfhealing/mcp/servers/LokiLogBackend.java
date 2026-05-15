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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real {@link LogBackend} that issues LogQL queries against a Loki HTTP API.
 *
 * <p>Active when {@code selfhealing.observability.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "selfhealing.observability.enabled", havingValue = "true")
public class LokiLogBackend implements LogBackend {

    private static final Logger log = LoggerFactory.getLogger(LokiLogBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String serviceDeploymentSuffix;
    private final HttpClient http;

    public LokiLogBackend(
            @Value("${selfhealing.loki.url:http://localhost:3100}") String baseUrl,
            @Value("${selfhealing.k8s.scope-deployment-suffix:-api}") String suffix) {
        this.baseUrl = baseUrl;
        this.serviceDeploymentSuffix = suffix;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        log.info("LokiLogBackend ready (baseUrl={})", baseUrl);
    }

    @Override
    public Map<String, Object> search(String scope, Map<String, Object> arguments) {
        String service = scope + serviceDeploymentSuffix;
        String severity = (String) arguments.getOrDefault("severity", "ERROR");
        String logQl =
                "{service=\""
                        + service
                        + "\", namespace=\"selfhealing\"} |~ \"(?i)"
                        + severity
                        + "\"";
        long end = Instant.now().toEpochMilli() * 1_000_000L; // Loki uses nanoseconds
        long start = end - Duration.ofMinutes(10).toNanos();
        String path =
                "/loki/api/v1/query_range?query="
                        + url(logQl)
                        + "&start="
                        + start
                        + "&end="
                        + end
                        + "&limit=50";
        JsonNode result;
        try {
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + path))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LogTimeoutException(
                        "Loki HTTP " + response.statusCode() + ": " + response.body());
            }
            result = MAPPER.readTree(response.body());
        } catch (LogTimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new LogTimeoutException("Loki query failed: " + e.getMessage());
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        JsonNode streams = result.path("data").path("result");
        if (streams.isArray()) {
            for (JsonNode stream : streams) {
                JsonNode values = stream.path("values");
                if (!values.isArray()) {
                    continue;
                }
                for (JsonNode v : values) {
                    if (v.isArray() && v.size() >= 2) {
                        String line = v.get(1).asText();
                        entries.add(
                                Map.of(
                                        "severity", severity,
                                        "message", line,
                                        "trace_id", extractTraceId(line)));
                        if (entries.size() >= 10) {
                            break;
                        }
                    }
                }
                if (entries.size() >= 10) {
                    break;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", scope);
        out.put("query", logQl);
        out.put("entries", entries);
        String summary =
                entries.isEmpty()
                        ? "no matching log entries"
                        : (entries.size() + " " + severity + " entries in last 10m");
        out.put("cluster_summary", summary);
        String signal =
                entries.stream()
                                .anyMatch(
                                        e ->
                                                String.valueOf(e.get("message"))
                                                        .contains("SocketTimeoutException"))
                        ? "SocketTimeoutException cluster on upstream ranker"
                        : (entries.isEmpty() ? "no errors in window" : summary);
        out.put("signal", signal);
        return out;
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String extractTraceId(String line) {
        int idx = line.indexOf("trace_id=");
        if (idx < 0) {
            return "";
        }
        int end = line.indexOf(' ', idx);
        return line.substring(idx + "trace_id=".length(), end < 0 ? line.length() : end);
    }

    public static class LogTimeoutException extends RuntimeException {
        public LogTimeoutException(String message) {
            super(message);
        }
    }
}

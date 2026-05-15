package com.sachingupta.selfhealing.mcp.servers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link MetricsBackend}. Scenarios drive the state by registering p99 trajectories per
 * service. Used when the real Prometheus integration is off (the default for tests without a
 * cluster).
 *
 * <p>Activated when {@code selfhealing.observability.enabled} is missing or {@code false}. In a
 * normal demo run with {@code --selfhealing.observability.enabled=true}, {@link
 * PrometheusMetricsBackend} replaces this bean.
 */
@Component
@ConditionalOnProperty(
        name = "selfhealing.observability.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class PrometheusFakeBackend implements MetricsBackend {

    public enum Trajectory {
        BASELINE,
        SPIKE,
        RECOVERED
    }

    private final Map<String, Trajectory> services = new HashMap<>();

    public void setTrajectory(String service, Trajectory trajectory) {
        services.put(service, trajectory);
    }

    @Override
    public Map<String, Object> rangeQuery(String scope, Map<String, Object> arguments) {
        Trajectory t = services.getOrDefault(scope, Trajectory.BASELINE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metric", arguments.getOrDefault("metric", "http_request_duration_p99_ms"));
        result.put("service", scope);
        result.put("series", seriesFor(t));
        result.put("shape", t == Trajectory.SPIKE ? "step at T-12m" : "flat");
        result.put("signal", t == Trajectory.SPIKE ? "p99 step at T-12m" : "p99 flat");
        return result;
    }

    @Override
    public Map<String, Object> instantQuery(String scope, Map<String, Object> arguments) {
        Trajectory t = services.getOrDefault(scope, Trajectory.BASELINE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metric", arguments.getOrDefault("metric", "http_request_duration_p99_ms"));
        result.put("service", scope);
        result.put("value", t == Trajectory.SPIKE ? 1850 : 220);
        result.put(
                "signal",
                t == Trajectory.RECOVERED
                        ? "p99 back to baseline"
                        : (t == Trajectory.SPIKE ? "p99 elevated" : "p99 nominal"));
        return result;
    }

    @Override
    public Map<String, Object> alertState(String scope) {
        Trajectory t = services.getOrDefault(scope, Trajectory.BASELINE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", scope);
        result.put(
                "alerts",
                t == Trajectory.SPIKE ? java.util.List.of("HighLatencyP99") : java.util.List.of());
        result.put("signal", t == Trajectory.SPIKE ? "HighLatencyP99 firing" : "no alerts");
        return result;
    }

    private static Object seriesFor(Trajectory t) {
        return switch (t) {
            case BASELINE -> "200ms..230ms across last hour";
            case SPIKE -> "210ms..230ms, then step to 1.8s at T-12m";
            case RECOVERED -> "1.8s, returning to 220ms in last minute";
        };
    }
}

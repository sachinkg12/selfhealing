package com.sachingupta.selfhealing.mcp.servers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link LogBackend}. Carries a per-service mode so scenarios can request rich results or
 * a deliberate timeout.
 *
 * <p>Activated when {@code selfhealing.observability.enabled} is missing or {@code false}. In a
 * normal demo run with {@code --selfhealing.observability.enabled=true}, {@link LokiLogBackend}
 * replaces this bean.
 */
@Component
@ConditionalOnProperty(
        name = "selfhealing.observability.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class LogFakeBackend implements LogBackend {

    public enum Mode {
        RICH,
        TIMEOUT
    }

    private final Map<String, Mode> modes = new java.util.HashMap<>();

    public void setMode(String service, Mode mode) {
        modes.put(service, mode);
    }

    @Override
    public Map<String, Object> search(String scope, Map<String, Object> arguments) {
        Mode mode = modes.getOrDefault(scope, Mode.RICH);
        Map<String, Object> result = new LinkedHashMap<>();
        if (mode == Mode.TIMEOUT) {
            throw new LogTimeoutException(
                    "log MCP timed out for scope '" + scope + "' with filter " + arguments);
        }
        result.put("service", scope);
        result.put("revision", arguments.getOrDefault("revision", "unknown"));
        result.put(
                "entries",
                List.of(
                        Map.of(
                                "severity", "ERROR",
                                "message",
                                        "SocketTimeoutException calling upstream ranker"
                                                + " (rev current)",
                                "trace_id", "trc-9a-4c1"),
                        Map.of(
                                "severity", "ERROR",
                                "message", "ranker.deadline_exceeded",
                                "trace_id", "trc-9a-4c2")));
        result.put("cluster_summary", "SocketTimeoutException on upstream ranker client");
        result.put("signal", "SocketTimeoutException cluster on upstream ranker");
        return result;
    }

    public static class LogTimeoutException extends RuntimeException {
        public LogTimeoutException(String message) {
            super(message);
        }
    }
}

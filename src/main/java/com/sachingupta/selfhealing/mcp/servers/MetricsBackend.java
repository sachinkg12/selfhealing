package com.sachingupta.selfhealing.mcp.servers;

import java.util.Map;

/**
 * Stable contract used by {@link PrometheusMcpServer}. Implementations adapt either a real
 * Prometheus HTTP API or an in-memory fake; the MCP layer is agnostic (Dependency Inversion).
 */
public interface MetricsBackend {

    Map<String, Object> rangeQuery(String scope, Map<String, Object> arguments);

    Map<String, Object> instantQuery(String scope, Map<String, Object> arguments);

    Map<String, Object> alertState(String scope);
}

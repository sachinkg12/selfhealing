package com.sachingupta.selfhealing.mcp.servers;

import java.util.Map;

/**
 * Stable contract used by {@link DeployMcpServer}. Implementations adapt a real (fabric8 over kind)
 * or in-memory deployment system; the MCP layer never depends on which one is active (Dependency
 * Inversion).
 *
 * <p>Every method returns a generic {@code Map<String, Object>} payload that the abstract MCP
 * server packages into an {@link com.sachingupta.selfhealing.verdict.McpResponse}. Payloads include
 * a {@code "signal"} key so the LLM and the confidence scorer see a short, hypothesis-friendly
 * label without having to parse the full structure.
 */
public interface DeployBackend {

    Map<String, Object> history(String scope);

    Map<String, Object> currentRevision(String scope);

    Map<String, Object> rollback(String scope, Map<String, Object> arguments);

    Map<String, Object> scale(String scope, Map<String, Object> arguments);

    Map<String, Object> restart(String scope);
}

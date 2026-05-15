package com.sachingupta.selfhealing.verdict;

import java.util.Map;

/**
 * One MCP tool invocation and its result, kept in the per-incident evidence ledger. {@code server}
 * is the short server name (e.g. {@code "prometheus"} for {@code prometheus-mcp}); {@code tool} is
 * the bare tool name (e.g. {@code "range_query"}). Downstream the two are combined as {@code
 * server.tool} for the verdict's evidence and missing-signals fields.
 */
public record McpResponse(
        String server,
        String tool,
        Map<String, Object> arguments,
        Object payload,
        String signal,
        long elapsedMs,
        String error) {

    public boolean ok() {
        return error == null;
    }

    /** Server-qualified tool name, e.g. {@code "prometheus.range_query"}. */
    public String qualifiedTool() {
        return server + "." + tool;
    }

    public static McpResponse success(
            String server,
            String tool,
            Map<String, Object> arguments,
            Object payload,
            String signal,
            long elapsedMs) {
        return new McpResponse(server, tool, arguments, payload, signal, elapsedMs, null);
    }

    public static McpResponse failure(
            String server,
            String tool,
            Map<String, Object> arguments,
            String error,
            long elapsedMs) {
        return new McpResponse(server, tool, arguments, null, "", elapsedMs, error);
    }
}

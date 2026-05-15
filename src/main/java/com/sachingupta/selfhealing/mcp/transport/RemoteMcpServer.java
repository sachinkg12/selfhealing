package com.sachingupta.selfhealing.mcp.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sachingupta.selfhealing.mcp.api.McpServer;
import com.sachingupta.selfhealing.mcp.api.McpTool;
import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.security.acl.AclDeniedException;
import com.sachingupta.selfhealing.security.oauth.TokenException;
import com.sachingupta.selfhealing.security.rbac.RbacDeniedException;
import com.sachingupta.selfhealing.verdict.McpResponse;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;

/**
 * Remote {@link McpServer} facade backed by the shared {@link McpSyncClient}. One instance per
 * logical server (e.g. {@code deploy-mcp}); the agent's existing {@code McpRegistry.findByName}
 * lookup is unchanged. Calls travel out as JSON-RPC 2.0 {@code tools/call} messages and the result
 * is unmarshalled into the same {@link McpResponse} shape the in-process path produces, so every
 * downstream consumer (Evidence Tracker, Confidence Scorer, Refusal Policy, Verdict Builder) keeps
 * working without edits (Open/Closed).
 *
 * <p>Per-invocation identity, scope, and approval token ride inside the {@code tools/call}
 * arguments under the {@link McpRequestEnvelope#ENVELOPE_KEY} reserved key. The server-side adapter
 * extracts them and rebuilds the matching {@link McpToolInvocation} before delegating to the
 * existing security envelope.
 */
public final class RemoteMcpServer implements McpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String name;
    private final List<McpTool> tools;
    private final McpSyncClient client;

    public RemoteMcpServer(String name, List<McpTool> tools, McpSyncClient client) {
        this.name = name;
        this.tools = List.copyOf(tools);
        this.client = client;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<McpTool> tools() {
        return tools;
    }

    @Override
    public McpResponse invoke(McpToolInvocation invocation) {
        long started = System.currentTimeMillis();
        String shortName = shortName();
        String qualified = shortName + "." + invocation.toolName();
        Map<String, Object> wireArgs =
                McpRequestEnvelope.wrap(
                        invocation.arguments(),
                        invocation.identity(),
                        invocation.scope(),
                        invocation.approvalToken());
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(qualified, wireArgs));
        } catch (RuntimeException e) {
            mapException(e);
            throw e;
        }
        long elapsed = System.currentTimeMillis() - started;
        return decode(result, shortName, invocation.toolName(), invocation.arguments(), elapsed);
    }

    private McpResponse decode(
            McpSchema.CallToolResult result,
            String shortName,
            String toolName,
            Map<String, Object> arguments,
            long elapsed) {
        String text = firstTextContent(result);
        if (Boolean.TRUE.equals(result.isError())) {
            String error = extractField(text, "error", text);
            // Re-raise envelope-equivalent exceptions so callers see the same type they would
            // see from an in-process call (RBAC, ACL, OAuth scenarios depend on this).
            rethrowIfRecognized(error);
            return McpResponse.failure(shortName, toolName, arguments, error, elapsed);
        }
        Map<String, Object> deserialized = deserialize(text);
        Object payload = deserialized.getOrDefault("payload", deserialized);
        String signal = (String) deserialized.getOrDefault("signal", "ok");
        return McpResponse.success(shortName, toolName, arguments, payload, signal, elapsed);
    }

    private static String firstTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "{}";
        }
        McpSchema.Content c = result.content().get(0);
        if (c instanceof McpSchema.TextContent t) {
            return t.text();
        }
        return "{}";
    }

    private static Map<String, Object> deserialize(String json) {
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("payload", json);
        }
    }

    private static String extractField(String json, String field, String fallback) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, MAP_TYPE);
            Object v = parsed.get(field);
            return v == null ? fallback : v.toString();
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * If the server-side error carries a recognised security-envelope exception name, re-raise as
     * the same Java type so scenario expectations (catch RbacDeniedException, AclDeniedException,
     * TokenException) keep working in http mode.
     */
    private static void rethrowIfRecognized(String error) {
        if (error == null) {
            return;
        }
        int colon = error.indexOf(':');
        if (colon < 0) {
            return;
        }
        String type = error.substring(0, colon).trim();
        String message = error.substring(colon + 1).trim();
        switch (type) {
            case "RbacDeniedException" -> throw new RbacDeniedException(message);
            case "AclDeniedException" -> throw new AclDeniedException(message);
            case "TokenException" -> throw new TokenException(message);
            default -> {
                // fall through — caller wraps as McpResponse.failure
            }
        }
    }

    private static void mapException(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        rethrowIfRecognized(msg);
        throw new RuntimeException("MCP call failed: " + msg, e);
    }

    private String shortName() {
        return name.endsWith("-mcp") ? name.substring(0, name.length() - 4) : name;
    }
}

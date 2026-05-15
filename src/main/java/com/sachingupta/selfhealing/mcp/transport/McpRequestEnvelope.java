package com.sachingupta.selfhealing.mcp.transport;

import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import com.sachingupta.selfhealing.security.identity.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wire-level helper that smuggles the agent's identity + scope + approval token through MCP's
 * {@code tools/call} payload. The MCP spec does not standardise authentication, and Spring AI 1.0.x
 * does not surface HTTP headers to the {@code SyncToolSpecification} handler in a thread-safe way
 * (the handler runs on a different thread than the original Spring MVC request, so {@code
 * RequestContextHolder} fails). Embedding the envelope under a reserved {@code _envelope} argument
 * key keeps the wire path simple, deterministic, and free of thread-context coupling.
 *
 * <p>This keeps the security envelope authoritative on the server side — RBAC, ACL, and OAuth
 * verification all run via the existing {@link
 * com.sachingupta.selfhealing.mcp.infrastructure.AbstractMcpServer} pipeline after the envelope is
 * unpacked.
 */
public final class McpRequestEnvelope {

    public static final String ENVELOPE_KEY = "_envelope";

    private static final String FIELD_PRINCIPAL_NAME = "principal_name";
    private static final String FIELD_PRINCIPAL_ROLE = "principal_role";
    private static final String FIELD_PRINCIPAL_SCOPES = "principal_scopes";
    private static final String FIELD_TOOL_SCOPE = "tool_scope";
    private static final String FIELD_APPROVAL_TOKEN = "approval_token";

    /** Wraps the caller's identity + scope + token into a fresh args map suitable for the wire. */
    public static Map<String, Object> wrap(
            Map<String, Object> arguments,
            AgentIdentity identity,
            String toolScope,
            String approvalToken) {
        Map<String, Object> wire = new HashMap<>(arguments);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put(FIELD_PRINCIPAL_NAME, identity.name());
        envelope.put(FIELD_PRINCIPAL_ROLE, identity.role().name());
        envelope.put(FIELD_PRINCIPAL_SCOPES, new ArrayList<>(identity.scopes()));
        envelope.put(FIELD_TOOL_SCOPE, toolScope == null ? "" : toolScope);
        if (approvalToken != null && !approvalToken.isBlank()) {
            envelope.put(FIELD_APPROVAL_TOKEN, approvalToken);
        }
        wire.put(ENVELOPE_KEY, envelope);
        return wire;
    }

    /**
     * Extracts the envelope from the incoming args map (server side). Returns a {@link Decoded}
     * pair: the reconstructed identity/scope/token and the cleaned argument map (with {@code
     * _envelope} stripped) suitable for the existing in-process {@code McpServer.invoke}.
     */
    @SuppressWarnings("unchecked")
    public static Decoded unwrap(Map<String, Object> wireArgs) {
        Object raw = wireArgs.get(ENVELOPE_KEY);
        if (!(raw instanceof Map)) {
            return new Decoded(
                    new AgentIdentity("unknown", Role.TRIAGE, Collections.emptySet()),
                    "",
                    null,
                    Collections.unmodifiableMap(new HashMap<>(wireArgs)));
        }
        Map<String, Object> envelope = (Map<String, Object>) raw;
        String name = stringField(envelope, FIELD_PRINCIPAL_NAME, "unknown");
        Role role = Role.valueOf(stringField(envelope, FIELD_PRINCIPAL_ROLE, "TRIAGE"));
        Set<String> scopes = stringSetField(envelope, FIELD_PRINCIPAL_SCOPES);
        String toolScope = stringField(envelope, FIELD_TOOL_SCOPE, "");
        String approvalToken = stringField(envelope, FIELD_APPROVAL_TOKEN, null);
        Map<String, Object> cleaned = new HashMap<>(wireArgs);
        cleaned.remove(ENVELOPE_KEY);
        return new Decoded(
                new AgentIdentity(name, role, scopes),
                toolScope,
                approvalToken,
                Collections.unmodifiableMap(cleaned));
    }

    public record Decoded(
            AgentIdentity identity,
            String toolScope,
            String approvalToken,
            Map<String, Object> arguments) {}

    private static String stringField(Map<String, Object> m, String key, String defaultValue) {
        Object v = m.get(key);
        return v == null ? defaultValue : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> stringSetField(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> list) {
            Set<String> out = new HashSet<>();
            for (Object o : list) {
                out.add(o.toString());
            }
            return out;
        }
        return Collections.emptySet();
    }

    private McpRequestEnvelope() {}
}

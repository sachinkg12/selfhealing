package com.sachingupta.selfhealing.security.identity;

import java.util.Set;

/** Identity an agent carries on every MCP call. Immutable. */
public record AgentIdentity(String name, Role role, Set<String> scopes) {

    public AgentIdentity {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("identity name required");
        }
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}

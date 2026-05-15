package com.sachingupta.selfhealing.security.acl;

import java.util.HashSet;
import java.util.Set;

/**
 * Specification-shaped allow-list of (tool, scope) pairs. Holds the fine-grained authorization
 * decisions that let a principal call a tool in one scope (e.g. {@code search}) but not another
 * (e.g. {@code auth}).
 */
public final class ToolAcl {

    private final Set<Entry> allowed = new HashSet<>();

    public ToolAcl permit(String toolName, String scope) {
        allowed.add(new Entry(toolName, scope));
        return this;
    }

    public boolean isAllowed(String toolName, String scope) {
        return allowed.contains(new Entry(toolName, scope));
    }

    private record Entry(String tool, String scope) {}
}

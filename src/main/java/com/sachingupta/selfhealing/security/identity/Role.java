package com.sachingupta.selfhealing.security.identity;

/** Coarse-grained role; refined further by {@code ToolAcl} at tool granularity. */
public enum Role {
    TRIAGE,
    REMEDIATOR
}

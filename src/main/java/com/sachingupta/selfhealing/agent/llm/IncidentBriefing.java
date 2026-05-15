package com.sachingupta.selfhealing.agent.llm;

import com.sachingupta.selfhealing.security.identity.AgentIdentity;

/**
 * Per-incident inputs handed to an {@link LlmPlanner}: the calling identity, a fresh approval token
 * for write actions, the natural-language description of the incident, and the scope (service
 * namespace).
 */
public record IncidentBriefing(
        AgentIdentity identity, String approvalToken, String description, String scope) {}

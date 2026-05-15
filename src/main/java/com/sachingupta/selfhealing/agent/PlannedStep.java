package com.sachingupta.selfhealing.agent;

import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;

/**
 * One step in the plan emitted by a {@link Planner}. Maps to one MCP tool call. The advisor
 * pipeline runs after every successful call, so planners can react to evidence as the loop
 * progresses.
 */
public record PlannedStep(ReasoningPhase phase, String serverName, McpToolInvocation invocation) {}

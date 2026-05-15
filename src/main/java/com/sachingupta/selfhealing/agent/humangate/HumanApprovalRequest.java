package com.sachingupta.selfhealing.agent.humangate;

import com.sachingupta.selfhealing.verdict.EvidenceRef;
import java.util.List;
import java.util.Map;

/**
 * Everything a human reviewer needs to decide whether the agent's proposed remediation is safe.
 * Constructed by the {@link com.sachingupta.selfhealing.agent.Agent} once the advisor pipeline has
 * cleared the machine gate and immediately before a write tool is invoked.
 *
 * @param incidentId stable identifier carried throughout the run
 * @param hypothesis the agent's stated root cause
 * @param confidence the advisor pipeline's computed score in [0, 1]
 * @param threshold the refusal threshold the pipeline applied
 * @param scope service namespace (e.g. {@code "search"})
 * @param proposedTool qualified tool name (e.g. {@code "deploy.rollback"})
 * @param audience MCP server audience the token must target (e.g. {@code "deploy-mcp"})
 * @param arguments arguments the agent intends to pass to the write tool
 * @param evidence read-side ledger entries that informed the verdict
 */
public record HumanApprovalRequest(
        String incidentId,
        String hypothesis,
        double confidence,
        double threshold,
        String scope,
        String proposedTool,
        String audience,
        Map<String, Object> arguments,
        List<EvidenceRef> evidence) {}

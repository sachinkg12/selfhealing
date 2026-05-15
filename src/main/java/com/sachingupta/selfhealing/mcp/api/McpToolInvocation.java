package com.sachingupta.selfhealing.mcp.api;

import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import java.util.Map;

/**
 * Command (Command pattern) representing one tool call request: who is calling, what tool, with
 * what arguments, in which scope, and the approval token if the tool is a write.
 */
public record McpToolInvocation(
        AgentIdentity identity,
        String toolName,
        String scope,
        Map<String, Object> arguments,
        String approvalToken) {

    public static McpToolInvocation read(
            AgentIdentity identity, String toolName, String scope, Map<String, Object> args) {
        return new McpToolInvocation(identity, toolName, scope, args, null);
    }

    public static McpToolInvocation write(
            AgentIdentity identity,
            String toolName,
            String scope,
            Map<String, Object> args,
            String approvalToken) {
        return new McpToolInvocation(identity, toolName, scope, args, approvalToken);
    }

    /**
     * Returns a copy of this invocation with the approval token replaced. Used by the agent at
     * write-invocation time to substitute the wire token minted by the human-approval gate into a
     * planner-emitted write invocation that was constructed without one.
     */
    public McpToolInvocation withApprovalToken(String newToken) {
        return new McpToolInvocation(identity, toolName, scope, arguments, newToken);
    }
}

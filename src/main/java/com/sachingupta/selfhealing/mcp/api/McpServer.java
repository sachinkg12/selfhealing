package com.sachingupta.selfhealing.mcp.api;

import com.sachingupta.selfhealing.verdict.McpResponse;
import java.util.List;

/**
 * Stable contract every MCP server implements. New servers plug into the agent by adding a new
 * {@link McpServer} bean; nothing in the agent or registry needs to change (Open/Closed).
 */
public interface McpServer {

    /** Stable identifier used as the OAuth audience claim. */
    String name();

    /** Tools the server exposes; consumed by capability discovery. */
    List<McpTool> tools();

    /**
     * Invoke a tool. Implementations are responsible for RBAC, ACL, OAuth, and audit. The agent
     * never bypasses this entry point.
     */
    McpResponse invoke(McpToolInvocation invocation);
}

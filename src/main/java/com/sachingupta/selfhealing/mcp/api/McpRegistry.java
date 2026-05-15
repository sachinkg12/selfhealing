package com.sachingupta.selfhealing.mcp.api;

import java.util.List;
import java.util.Optional;

/**
 * Capability discovery surface used by the agent. Mirrors MCP's session-start listTools call
 * (Section II).
 */
public interface McpRegistry {

    List<McpServer> servers();

    Optional<McpServer> findByName(String name);
}

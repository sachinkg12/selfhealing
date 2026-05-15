package com.sachingupta.selfhealing.mcp.api;

/**
 * Declarative description of one MCP tool. {@code write=true} marks the tool as destructive and
 * triggers the human-in-the-loop approval gate.
 */
public record McpTool(String name, boolean write, String description) {

    public static McpTool read(String name, String description) {
        return new McpTool(name, false, description);
    }

    public static McpTool write(String name, String description) {
        return new McpTool(name, true, description);
    }
}

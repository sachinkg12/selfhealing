package com.sachingupta.selfhealing.agent.llm;

import com.sachingupta.selfhealing.mcp.api.McpRegistry;
import com.sachingupta.selfhealing.mcp.api.McpServer;
import com.sachingupta.selfhealing.mcp.api.McpTool;
import org.springframework.stereotype.Component;

/**
 * Renders the registry into the textual tool catalogue the LLM sees. Kept separate from {@link
 * LlmPlanner} so the catalogue formatting can evolve without touching the planner (Single
 * Responsibility).
 */
@Component
public class ToolCatalogue {

    private final McpRegistry registry;

    public ToolCatalogue(McpRegistry registry) {
        this.registry = registry;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (McpServer server : registry.servers()) {
            for (McpTool tool : server.tools()) {
                sb.append("- ")
                        .append(server.name())
                        .append(".")
                        .append(tool.name())
                        .append(tool.write() ? " (write, requires approval token)" : " (read)")
                        .append(" — ")
                        .append(tool.description())
                        .append('\n');
            }
        }
        return sb.toString().trim();
    }
}

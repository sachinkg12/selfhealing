package com.sachingupta.selfhealing.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sachingupta.selfhealing.mcp.api.McpServer;
import com.sachingupta.selfhealing.mcp.api.McpTool;
import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.verdict.McpResponse;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adapter (GoF) that exposes our in-process {@link McpServer} beans through Spring AI's MCP server
 * runtime as real JSON-RPC 2.0 tools over Streamable HTTP. Active only when {@code
 * selfhealing.mcp.mode=http}.
 *
 * <p>Each (server, tool) pair becomes one {@link SyncToolSpecification} whose qualified name is
 * {@code <shortServerName>.<toolName>} (e.g. {@code prometheus.range_query}). The handler unpacks
 * the per-call {@link McpRequestEnvelope} from the {@code tools/call} arguments, rebuilds the
 * matching {@link McpToolInvocation}, and delegates to {@link McpServer#invoke} so the existing
 * RBAC → ACL → OAuth → execute pipeline runs unchanged on the server side.
 */
@Configuration
@ConditionalOnProperty(name = "selfhealing.mcp.mode", havingValue = "http")
public class McpServerAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpServerAdapter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public List<SyncToolSpecification> mcpToolSpecifications(List<McpServer> servers) {
        List<SyncToolSpecification> specs = new ArrayList<>();
        for (McpServer server : servers) {
            String shortName = shortName(server.name());
            for (McpTool tool : server.tools()) {
                specs.add(toSpecification(server, shortName, tool));
            }
        }
        log.info(
                "MCP server adapter registered {} tools across {} servers",
                specs.size(),
                servers.size());
        return specs;
    }

    private SyncToolSpecification toSpecification(
            McpServer server, String shortName, McpTool tool) {
        String qualified = shortName + "." + tool.name();
        McpSchema.Tool schemaTool =
                new McpSchema.Tool(qualified, tool.description(), inputSchemaJson());
        return new SyncToolSpecification(
                schemaTool,
                (exchange, args) -> {
                    try {
                        McpRequestEnvelope.Decoded decoded = McpRequestEnvelope.unwrap(args);
                        McpToolInvocation invocation =
                                tool.write()
                                        ? McpToolInvocation.write(
                                                decoded.identity(),
                                                tool.name(),
                                                decoded.toolScope(),
                                                decoded.arguments(),
                                                decoded.approvalToken())
                                        : McpToolInvocation.read(
                                                decoded.identity(),
                                                tool.name(),
                                                decoded.toolScope(),
                                                decoded.arguments());
                        McpResponse response = server.invoke(invocation);
                        return marshalResult(response);
                    } catch (RuntimeException e) {
                        log.warn(
                                "MCP tool {} failed: {}: {}",
                                qualified,
                                e.getClass().getSimpleName(),
                                e.getMessage());
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(
                                        errorJson(
                                                e.getClass().getSimpleName()
                                                        + ": "
                                                        + e.getMessage()))
                                .isError(true)
                                .build();
                    }
                });
    }

    private McpSchema.CallToolResult marshalResult(McpResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(json)
                    .isError(!response.ok())
                    .build();
        } catch (JsonProcessingException e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(errorJson("response serialization failed: " + e.getMessage()))
                    .isError(true)
                    .build();
        }
    }

    private static String errorJson(String message) {
        return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
    }

    private static String inputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":true}";
    }

    private static String shortName(String name) {
        return name.endsWith("-mcp") ? name.substring(0, name.length() - 4) : name;
    }
}

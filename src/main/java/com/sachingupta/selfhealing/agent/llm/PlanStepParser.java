package com.sachingupta.selfhealing.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sachingupta.selfhealing.agent.PlannedStep;
import com.sachingupta.selfhealing.agent.ReasoningPhase;
import com.sachingupta.selfhealing.mcp.api.McpRegistry;
import com.sachingupta.selfhealing.mcp.api.McpServer;
import com.sachingupta.selfhealing.mcp.api.McpTool;
import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Parses the JSON object emitted by the LLM into a {@link PlannedStep}, resolving the named
 * server/tool against the registry. Returns empty when the LLM signals it is done.
 *
 * <p>Encapsulated in its own class so the wire format can evolve without touching the planner
 * (Single Responsibility).
 */
@Component
public class PlanStepParser {

    private final ObjectMapper objectMapper;
    private final McpRegistry registry;

    public PlanStepParser(ObjectMapper objectMapper, McpRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    public Optional<PlannedStep> parse(String llmOutput, IncidentBriefing briefing) {
        String cleaned = stripMarkdownFences(llmOutput);
        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new LlmPlanParseException(
                    "LLM output is not valid JSON: " + e.getMessage() + "; raw: " + llmOutput);
        }
        if (root.hasNonNull("action") && "done".equalsIgnoreCase(root.get("action").asText())) {
            return Optional.empty();
        }

        String serverName = textOrThrow(root, "server");
        String toolName = textOrThrow(root, "tool");
        ReasoningPhase phase = ReasoningPhase.valueOf(textOrThrow(root, "phase").toUpperCase());
        Map<String, Object> arguments = toMap(root.get("arguments"));

        McpServer server =
                registry.findByName(serverName)
                        .orElseThrow(
                                () ->
                                        new LlmPlanParseException(
                                                "LLM picked unknown server '"
                                                        + serverName
                                                        + "'; raw: "
                                                        + llmOutput));
        McpTool tool = lookupTool(server, toolName, llmOutput);

        McpToolInvocation invocation =
                tool.write()
                        ? McpToolInvocation.write(
                                briefing.identity(),
                                toolName,
                                briefing.scope(),
                                arguments,
                                briefing.approvalToken())
                        : McpToolInvocation.read(
                                briefing.identity(), toolName, briefing.scope(), arguments);
        return Optional.of(new PlannedStep(phase, serverName, invocation));
    }

    private static String stripMarkdownFences(String s) {
        String t = s.trim();
        // Strip leading markdown fence if present.
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                t = t.substring(firstNl + 1);
            }
            int closing = t.lastIndexOf("```");
            if (closing >= 0) {
                t = t.substring(0, closing).trim();
            }
            return t;
        }
        // Otherwise, extract the LAST top-level JSON object embedded in prose.
        // Small models often add explanatory prose before the JSON object even
        // when told not to; recovering gracefully is cheaper than re-asking.
        int firstBrace = t.indexOf('{');
        int lastBrace = t.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return t.substring(firstBrace, lastBrace + 1);
        }
        return t;
    }

    private static String textOrThrow(JsonNode root, String field) {
        if (!root.hasNonNull(field)) {
            throw new LlmPlanParseException(
                    "LLM output missing required field '" + field + "': " + root);
        }
        return root.get(field).asText();
    }

    private static Map<String, Object> toMap(JsonNode args) {
        Map<String, Object> result = new HashMap<>();
        if (args == null || args.isNull()) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> it = args.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isInt()) {
                result.put(e.getKey(), v.asInt());
            } else if (v.isDouble()) {
                result.put(e.getKey(), v.asDouble());
            } else if (v.isBoolean()) {
                result.put(e.getKey(), v.asBoolean());
            } else {
                result.put(e.getKey(), v.asText());
            }
        }
        return result;
    }

    private static McpTool lookupTool(McpServer server, String toolName, String raw) {
        return server.tools().stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst()
                .orElseThrow(
                        () ->
                                new LlmPlanParseException(
                                        "LLM picked unknown tool '"
                                                + toolName
                                                + "' on server '"
                                                + server.name()
                                                + "'; raw: "
                                                + raw));
    }
}

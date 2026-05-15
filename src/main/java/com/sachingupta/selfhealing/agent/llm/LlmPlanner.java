package com.sachingupta.selfhealing.agent.llm;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.agent.PlannedStep;
import com.sachingupta.selfhealing.agent.Planner;
import com.sachingupta.selfhealing.verdict.McpResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Strategy implementation of {@link Planner} that asks an LLM (via Spring AI {@link ChatModel}) for
 * the next step on every turn. The LLM sees the tool catalogue and the current evidence ledger; it
 * responds with one JSON object describing the next call. {@link PlanStepParser} converts that JSON
 * into a {@link PlannedStep}.
 *
 * <p>Per-incident state: the planner is built fresh by {@link LlmPlannerFactory} for each run so it
 * can keep a small dedup set of tools the LLM has already invoked. If the LLM proposes a tool that
 * has already returned a successful result in the ledger, the planner refuses the step and asks the
 * LLM for a different action. This contains the well-known small-model failure mode of looping on
 * the same read after the result is already in context.
 */
public final class LlmPlanner implements Planner {

    private static final Logger log = LoggerFactory.getLogger(LlmPlanner.class);
    private static final int MAX_DEDUP_RETRIES = 3;

    private final ChatModel chatModel;
    private final ToolCatalogue toolCatalogue;
    private final LedgerRenderer ledgerRenderer;
    private final PlanStepParser parser;
    private final IncidentBriefing briefing;
    private final String systemPrompt;

    LlmPlanner(
            ChatModel chatModel,
            ToolCatalogue toolCatalogue,
            LedgerRenderer ledgerRenderer,
            PlanStepParser parser,
            IncidentBriefing briefing) {
        this.chatModel = chatModel;
        this.toolCatalogue = toolCatalogue;
        this.ledgerRenderer = ledgerRenderer;
        this.parser = parser;
        this.briefing = briefing;
        this.systemPrompt =
                String.format(PromptTemplates.SYSTEM_PROMPT_TEMPLATE, toolCatalogue.render());
    }

    @Override
    public List<PlannedStep> initialPlan(ReasoningContext context) {
        return askNext(context).map(List::of).orElse(List.of());
    }

    @Override
    public Optional<PlannedStep> nextStep(ReasoningContext context, int stepIndex) {
        return askNext(context);
    }

    private Optional<PlannedStep> askNext(ReasoningContext context) {
        Set<String> alreadyCalled = successfulToolNames(context);
        String extraGuidance = "";
        for (int attempt = 0; attempt < MAX_DEDUP_RETRIES; attempt++) {
            Optional<PlannedStep> proposed = oneCall(context, extraGuidance);
            if (proposed.isEmpty()) {
                return proposed;
            }
            String toolName = proposed.get().invocation().toolName();
            if (!alreadyCalled.contains(toolName)) {
                return proposed;
            }
            log.info(
                    "  LLM proposed already-called tool '{}'; re-asking (attempt {}/{})",
                    toolName,
                    attempt + 1,
                    MAX_DEDUP_RETRIES);
            extraGuidance =
                    "\n\nYou already called the following tools successfully (their results are in"
                            + " the ledger above): "
                            + alreadyCalled
                            + ". Do NOT call any of these again. Pick the NEXT workflow step you"
                            + " have not yet completed.";
        }
        // Fall through: return whatever was proposed last; the agent's max-steps cap is the final
        // safety net.
        return oneCall(context, extraGuidance);
    }

    private Optional<PlannedStep> oneCall(ReasoningContext context, String extraGuidance) {
        String userPrompt =
                String.format(
                        PromptTemplates.USER_PROMPT_TEMPLATE,
                        briefing.description(),
                        briefing.scope(),
                        ledgerRenderer.render(context.ledger()));
        if (!extraGuidance.isEmpty()) {
            userPrompt = userPrompt + extraGuidance;
        }
        Prompt prompt =
                new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
        long t0 = System.currentTimeMillis();
        ChatResponse response = chatModel.call(prompt);
        long elapsedMs = System.currentTimeMillis() - t0;
        String content = response.getResult().getOutput().getText();
        log.info(
                "  LLM planner step (latency={}ms, tokens used: input={}, output={})",
                elapsedMs,
                response.getMetadata().getUsage().getPromptTokens(),
                response.getMetadata().getUsage().getCompletionTokens());
        return parser.parse(content, briefing);
    }

    private static Set<String> successfulToolNames(ReasoningContext context) {
        Set<String> names = new LinkedHashSet<>();
        for (McpResponse r : context.ledger().entries()) {
            if (r.ok()) {
                names.add(r.tool());
            }
        }
        return names;
    }
}

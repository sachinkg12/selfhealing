package com.sachingupta.selfhealing.agent.llm;

import com.sachingupta.selfhealing.agent.Planner;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * Factory (Factory pattern) for {@link LlmPlanner}. Scenarios pass a per-incident {@link
 * IncidentBriefing} and get back a planner already primed with the tool catalogue and the calling
 * identity.
 */
@Component
public class LlmPlannerFactory {

    private final ChatModel chatModel;
    private final ToolCatalogue toolCatalogue;
    private final LedgerRenderer ledgerRenderer;
    private final PlanStepParser parser;

    public LlmPlannerFactory(
            ChatModel chatModel,
            ToolCatalogue toolCatalogue,
            LedgerRenderer ledgerRenderer,
            PlanStepParser parser) {
        this.chatModel = chatModel;
        this.toolCatalogue = toolCatalogue;
        this.ledgerRenderer = ledgerRenderer;
        this.parser = parser;
    }

    public Planner forIncident(IncidentBriefing briefing) {
        return new LlmPlanner(chatModel, toolCatalogue, ledgerRenderer, parser, briefing);
    }
}

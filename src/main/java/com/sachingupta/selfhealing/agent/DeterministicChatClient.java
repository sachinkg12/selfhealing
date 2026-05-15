package com.sachingupta.selfhealing.agent;

import org.springframework.stereotype.Component;

/**
 * Stand-in for a real LLM call. The demo's reasoning steps are encoded directly in {@link
 * com.sachingupta.selfhealing.agent.PlannedStep} sequences emitted by scenario-specific {@link
 * Planner}s, so this client only carries diagnostic identity.
 */
@Component
public class DeterministicChatClient implements ChatClient {

    @Override
    public String describe() {
        return "deterministic-planner (no LLM invoked)";
    }
}

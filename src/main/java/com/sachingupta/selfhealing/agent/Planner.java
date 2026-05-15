package com.sachingupta.selfhealing.agent;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import java.util.List;
import java.util.Optional;

/**
 * Decides what the agent does next. Real deployments use an LLM with MCP tool discovery; the demo
 * can also run scenario-specific deterministic planners that emit a fixed step sequence.
 */
public interface Planner {

    /** Plan a sequence of read steps before the first verdict is taken. */
    List<PlannedStep> initialPlan(ReasoningContext context);

    /**
     * Called after each step. Returns an additional step to run (for example, the gated rollback or
     * a verify), or empty when the agent has finished.
     */
    Optional<PlannedStep> nextStep(ReasoningContext context, int stepIndex);
}

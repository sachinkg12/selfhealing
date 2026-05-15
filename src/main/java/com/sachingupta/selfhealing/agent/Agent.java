package com.sachingupta.selfhealing.agent;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.advisor.pipeline.AdvisorPipeline;
import com.sachingupta.selfhealing.agent.humangate.HumanApprovalGate;
import com.sachingupta.selfhealing.agent.humangate.HumanApprovalOutcome;
import com.sachingupta.selfhealing.agent.humangate.HumanApprovalRequest;
import com.sachingupta.selfhealing.mcp.api.McpRegistry;
import com.sachingupta.selfhealing.mcp.api.McpServer;
import com.sachingupta.selfhealing.mcp.api.McpTool;
import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.verdict.Action;
import com.sachingupta.selfhealing.verdict.Decision;
import com.sachingupta.selfhealing.verdict.McpResponse;
import com.sachingupta.selfhealing.verdict.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin coordinator. Owns no policy of its own: the planner decides which tools to call, the MCP
 * servers enforce security, the advisor pipeline produces the machine verdict, and the {@link
 * HumanApprovalGate} authorises (or refuses) the first write. Following the Single Responsibility
 * Principle, this class only sequences those collaborators.
 *
 * <p><b>Verdict-and-human gate writes.</b> When the planner emits the first write step, the agent
 * records the proposed action on the {@link ReasoningContext} and runs the advisor pipeline. If the
 * resulting decision is {@code refuse}, the run halts immediately and the refusal verdict is
 * returned; no write is invoked and no human is paged. If the machine clears, the agent calls the
 * human-approval gate with the full reasoning context (hypothesis, confidence, evidence, proposed
 * write). On approve the gate returns a freshly-minted, audience-scoped OAuth token, which the
 * agent substitutes into the planner's write invocation. On deny or timeout the verdict is flipped
 * to {@code refuse} with a {@code human-approval(...)} missing-signal entry, and the write is never
 * invoked.
 *
 * <p>A {@code maxSteps} safety net bounds the total number of MCP invocations the agent will
 * perform in a single run, so a runaway planner (LLM or otherwise) cannot loop indefinitely. The
 * cap is intentionally configurable via {@code selfhealing.agent.max-steps}.
 */
@Component
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final McpRegistry registry;
    private final AdvisorPipeline pipeline;
    private final HumanApprovalGate humanGate;
    private final int maxSteps;

    public Agent(
            McpRegistry registry,
            AdvisorPipeline pipeline,
            HumanApprovalGate humanGate,
            @Value("${selfhealing.agent.max-steps:12}") int maxSteps) {
        this.registry = registry;
        this.pipeline = pipeline;
        this.humanGate = humanGate;
        this.maxSteps = maxSteps;
    }

    public Verdict run(ReasoningContext context, Planner planner) {
        int stepsTaken = 0;
        boolean gated = false;

        for (PlannedStep step : planner.initialPlan(context)) {
            if (stepsTaken >= maxSteps) {
                log.warn("max-steps cap of {} reached during initial plan; stopping", maxSteps);
                break;
            }
            gated = ensureGated(step, context, gated);
            if (context.refused()) {
                return context.verdict().build();
            }
            invoke(step, context);
            stepsTaken++;
        }

        int idx = 0;
        while (stepsTaken < maxSteps) {
            var maybeNext = planner.nextStep(context, idx);
            if (maybeNext.isEmpty()) {
                break;
            }
            PlannedStep step = maybeNext.get();
            gated = ensureGated(step, context, gated);
            if (context.refused()) {
                return context.verdict().build();
            }
            invoke(step, context);
            idx++;
            stepsTaken++;
        }

        if (stepsTaken >= maxSteps) {
            log.warn(
                    "max-steps cap of {} reached; closing run with the evidence accumulated so"
                            + " far",
                    maxSteps);
        }

        // If the run never crossed a write boundary, compute the verdict now over the reads.
        if (!gated) {
            pipeline.run(context);
        }
        return context.verdict().build();
    }

    /**
     * Returns whether the verdict has been computed. If we are about to execute the first write
     * step and the verdict has not yet been computed:
     *
     * <ol>
     *   <li>set the proposed action on the context (no token yet) and run the advisor pipeline;
     *   <li>if the machine refused, halt — no human is paged, no write happens;
     *   <li>otherwise call the human-approval gate; on approve, stash the minted token on the
     *       context and rewrite the action block to carry it; on deny or timeout, flip the verdict
     *       to refuse with a {@code human-approval(...)} missing-signal entry.
     * </ol>
     */
    private boolean ensureGated(PlannedStep step, ReasoningContext context, boolean alreadyGated) {
        if (alreadyGated) {
            return true;
        }
        if (!isWriteStep(step)) {
            return false;
        }
        McpServer server = lookupServer(step);
        String shortName = shortServerName(server.name());
        String qualifiedTool = shortName + "." + step.invocation().toolName();
        Action proposed = new Action(qualifiedTool, step.invocation().arguments(), null);
        context.setProposedAction(proposed);
        log.info("  verdict gate: proposing {} (advisor pipeline running)", qualifiedTool);
        pipeline.run(context);

        if (context.refused()) {
            log.info("  verdict gate: machine refused before paging a human; no write executed");
            return true;
        }

        HumanApprovalRequest approvalRequest =
                new HumanApprovalRequest(
                        context.incidentId(),
                        context.verdict().currentHypothesis(),
                        context.computedConfidence(),
                        context.verdict().currentThreshold(),
                        step.invocation().scope(),
                        qualifiedTool,
                        server.name(),
                        step.invocation().arguments(),
                        context.verdict().currentEvidence());
        log.info("  human gate: requesting approval for {}", qualifiedTool);
        HumanApprovalOutcome outcome = humanGate.requestApproval(approvalRequest);

        if (!outcome.approved()) {
            log.info(
                    "  human gate: {} by {} — flipping verdict to refuse",
                    outcome.reason(),
                    outcome.approver());
            context.markRefused();
            context.verdict().decision(Decision.REFUSE);
            context.verdict().action(null);
            context.verdict()
                    .addMissingSignal(
                            "human-approval(action="
                                    + qualifiedTool
                                    + ", reason="
                                    + outcome.reason()
                                    + ")");
            context.verdict()
                    .addNextStep(
                            "re-request human approval for "
                                    + qualifiedTool
                                    + " with fresher evidence");
            return true;
        }

        log.info("  human gate: APPROVED by {} — wire token minted (60s)", outcome.approver());
        context.setHumanApprovedToken(outcome.wireToken());
        context.setProposedAction(
                new Action(qualifiedTool, step.invocation().arguments(), outcome.wireToken()));
        context.verdict()
                .action(
                        new Action(
                                qualifiedTool, step.invocation().arguments(), outcome.wireToken()));
        return true;
    }

    private void invoke(PlannedStep step, ReasoningContext context) {
        McpServer server = lookupServer(step);
        String shortName = shortServerName(server.name());
        McpToolInvocation invocation = step.invocation();
        if (isWriteStep(step)) {
            final McpToolInvocation captured = invocation;
            String wireToken =
                    context.humanApprovedToken()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "write step "
                                                            + shortName
                                                            + "."
                                                            + captured.toolName()
                                                            + " reached invoke() without a"
                                                            + " human-approved wire token —"
                                                            + " human-gate logic skipped?"));
            invocation = invocation.withApprovalToken(wireToken);
        }
        log.info(
                "[{}] {} → {}.{} (scope={})",
                step.phase(),
                invocation.identity().name(),
                server.name(),
                invocation.toolName(),
                invocation.scope());
        try {
            McpResponse response = server.invoke(invocation);
            context.ledger().record(response);
            if (isWriteStep(step)) {
                context.recordWriteAction(
                        new Action(
                                shortName + "." + invocation.toolName(),
                                invocation.arguments(),
                                invocation.approvalToken()));
            }
        } catch (RuntimeException e) {
            McpResponse failure =
                    McpResponse.failure(
                            shortName,
                            invocation.toolName(),
                            invocation.arguments(),
                            e.getClass().getSimpleName() + ": " + e.getMessage(),
                            0);
            context.ledger().record(failure);
            log.warn("    ↳ failed: {}", failure.error());
        }
    }

    private McpServer lookupServer(PlannedStep step) {
        return registry.findByName(step.serverName())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "unknown MCP server: " + step.serverName()));
    }

    /**
     * Decides whether {@code step} invokes a destructive tool by consulting the MCP server's
     * declared tool catalogue. This is the authoritative classification: a step is a write iff the
     * server says the tool is a write, regardless of whether the planner happened to attach a token
     * to the invocation.
     */
    private boolean isWriteStep(PlannedStep step) {
        McpServer server = lookupServer(step);
        for (McpTool tool : server.tools()) {
            if (tool.name().equals(step.invocation().toolName())) {
                return tool.write();
            }
        }
        throw new IllegalStateException(
                "unknown tool " + step.invocation().toolName() + " on server " + server.name());
    }

    private static String shortServerName(String name) {
        return name.endsWith("-mcp") ? name.substring(0, name.length() - 4) : name;
    }
}

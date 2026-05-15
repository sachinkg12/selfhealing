package com.sachingupta.selfhealing.scenarios;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.advisor.evidence.EvidenceLedger;
import com.sachingupta.selfhealing.agent.Agent;
import com.sachingupta.selfhealing.agent.PlannedStep;
import com.sachingupta.selfhealing.agent.Planner;
import com.sachingupta.selfhealing.agent.ReasoningPhase;
import com.sachingupta.selfhealing.agent.llm.IncidentBriefing;
import com.sachingupta.selfhealing.agent.llm.LlmPlannerFactory;
import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.mcp.servers.DeployMcpServer;
import com.sachingupta.selfhealing.mcp.servers.LogFakeBackend;
import com.sachingupta.selfhealing.mcp.servers.LogMcpServer;
import com.sachingupta.selfhealing.mcp.servers.NotificationMcpServer;
import com.sachingupta.selfhealing.mcp.servers.PrometheusFakeBackend;
import com.sachingupta.selfhealing.mcp.servers.PrometheusMcpServer;
import com.sachingupta.selfhealing.security.acl.PrincipalAclResolver;
import com.sachingupta.selfhealing.security.acl.ToolAcl;
import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import com.sachingupta.selfhealing.verdict.Verdict;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Scenario 1: latency regression → deploy correlation → log triage → verdict → human-approved
 * rollback → notify → verify. End-to-end remediation loop.
 *
 * <p>The planner is chosen at runtime by {@code selfhealing.planner.mode}: {@code deterministic}
 * uses the dynamic sequence below (no LLM cost); {@code llm} delegates the per-step decision to
 * Anthropic Claude through {@link LlmPlannerFactory}.
 *
 * <p>The deploy backend (fake or real Kubernetes) is selected by {@code selfhealing.k8s.enabled}.
 * The scenario does not depend on the concrete backend type — both the deterministic planner and
 * the LLM planner read the rollback target dynamically from the {@code history} response in the
 * evidence ledger.
 */
@Component
public class HappyPathScenario implements Scenario {

    private static final Logger log = LoggerFactory.getLogger(HappyPathScenario.class);

    private static final String SERVICE = "search-api";
    private static final String SCOPE = "search";
    private static final String INCIDENT = "INC-2026-0515-0001";

    private final Agent agent;
    private final ObjectProvider<PrometheusFakeBackend> prometheusFakeProvider;
    private final ObjectProvider<LogFakeBackend> logFakeProvider;
    private final PrincipalAclResolver aclResolver;
    private final LlmPlannerFactory llmPlannerFactory;
    private final String plannerMode;

    public HappyPathScenario(
            Agent agent,
            ObjectProvider<PrometheusFakeBackend> prometheusFakeProvider,
            ObjectProvider<LogFakeBackend> logFakeProvider,
            PrincipalAclResolver aclResolver,
            LlmPlannerFactory llmPlannerFactory,
            @Value("${selfhealing.planner.mode:deterministic}") String plannerMode) {
        this.agent = agent;
        this.prometheusFakeProvider = prometheusFakeProvider;
        this.logFakeProvider = logFakeProvider;
        this.aclResolver = aclResolver;
        this.llmPlannerFactory = llmPlannerFactory;
        this.plannerMode = plannerMode;
    }

    @Override
    public String slug() {
        return "happy-path";
    }

    @Override
    public String title() {
        return "Latency regression → confidence-scored rollback → notify → verify";
    }

    @Override
    public String claim() {
        return "End-to-end remediation loop";
    }

    @Override
    public void run() {
        AgentIdentity remediator = Identities.searchRemediator();
        aclResolver.install(
                remediator,
                new ToolAcl()
                        .permit("range_query", SCOPE)
                        .permit("instant_query", SCOPE)
                        .permit("alert_state", SCOPE)
                        .permit("history", SCOPE)
                        .permit("current_revision", SCOPE)
                        .permit("rollback", SCOPE)
                        .permit("search", SCOPE)
                        .permit("post", SCOPE)
                        .permit("thread_reply", SCOPE));

        // Drive fake backends into the right initial state when present.
        // In real-observability mode these beans are absent — the cluster
        // itself carries the spike state (v2 manifest sets SPIKE_MODE=true).
        PrometheusFakeBackend prometheusFake = prometheusFakeProvider.getIfAvailable();
        if (prometheusFake != null) {
            prometheusFake.setTrajectory(SCOPE, PrometheusFakeBackend.Trajectory.SPIKE);
        }
        LogFakeBackend logFake = logFakeProvider.getIfAvailable();
        if (logFake != null) {
            logFake.setMode(SCOPE, LogFakeBackend.Mode.RICH);
        }

        ReasoningContext context =
                new ReasoningContext(INCIDENT, remediator, new EvidenceLedger())
                        .expect("range_query")
                        .expect("history")
                        .expect("search");
        context.verdict().hypothesis("post-deploy regression on search-api");

        // No pre-minted token: the agent's HumanApprovalGate mints one in the human reviewer's
        // name at the gate point, and the agent substitutes it into the write invocation.
        Planner planner = pickPlanner(remediator);
        log.info("Planner mode: {} ({})", plannerMode, planner.getClass().getSimpleName());

        Verdict verdict = agent.run(context, planner);

        log.info("");
        log.info("Verdict:\n{}", verdict.toJson());
    }

    private Planner pickPlanner(AgentIdentity remediator) {
        if ("llm".equalsIgnoreCase(plannerMode)) {
            return llmPlannerFactory.forIncident(
                    new IncidentBriefing(
                            remediator,
                            null,
                            "p99 latency spike on search-api; a recent rollout may have"
                                    + " introduced a regression. Investigate and remediate.",
                            SCOPE));
        }
        return new HappyPathPlanner(remediator);
    }

    /**
     * Deterministic planner that walks the workflow but reads the rollback target from the {@code
     * history} response, so it works against both the fake and the real Kubernetes backend. The
     * write step is emitted with a {@code null} approval token; the agent substitutes a freshly
     * minted, human-approved token at invocation time.
     */
    private record HappyPathPlanner(AgentIdentity identity) implements Planner {

        @Override
        public List<PlannedStep> initialPlan(ReasoningContext context) {
            return List.of(
                    new PlannedStep(
                            ReasoningPhase.DETECT,
                            PrometheusMcpServer.NAME,
                            McpToolInvocation.read(
                                    identity,
                                    "range_query",
                                    SCOPE,
                                    Map.of(
                                            "metric",
                                            "http_request_duration_p99_ms",
                                            "service",
                                            SERVICE))),
                    new PlannedStep(
                            ReasoningPhase.CORRELATE,
                            DeployMcpServer.NAME,
                            McpToolInvocation.read(
                                    identity,
                                    "history",
                                    SCOPE,
                                    Map.of("service", SERVICE, "window", "1h"))),
                    new PlannedStep(
                            ReasoningPhase.HYPOTHESIZE,
                            LogMcpServer.NAME,
                            McpToolInvocation.read(
                                    identity,
                                    "search",
                                    SCOPE,
                                    Map.of("service", SERVICE, "severity", "ERROR"))));
        }

        @Override
        public Optional<PlannedStep> nextStep(ReasoningContext context, int stepIndex) {
            return switch (stepIndex) {
                case 0 -> Optional.of(buildRollback(context));
                case 1 ->
                        Optional.of(
                                new PlannedStep(
                                        ReasoningPhase.REMEDIATE,
                                        NotificationMcpServer.NAME,
                                        McpToolInvocation.read(
                                                identity,
                                                "post",
                                                SCOPE,
                                                Map.of(
                                                        "channel",
                                                        "#oncall",
                                                        "body",
                                                        "rolled back search-api;"
                                                                + " agent confidence 0.87"))));
                case 2 ->
                        Optional.of(
                                new PlannedStep(
                                        ReasoningPhase.VERIFY,
                                        PrometheusMcpServer.NAME,
                                        McpToolInvocation.read(
                                                identity,
                                                "instant_query",
                                                SCOPE,
                                                Map.of(
                                                        "metric",
                                                        "http_request_duration_p99_ms",
                                                        "service",
                                                        SERVICE))));
                default -> Optional.empty();
            };
        }

        private PlannedStep buildRollback(ReasoningContext context) {
            String prevRev = PreviousRevisionReader.read(context).orElse("rev-unknown");
            return new PlannedStep(
                    ReasoningPhase.REMEDIATE,
                    DeployMcpServer.NAME,
                    McpToolInvocation.write(
                            identity,
                            "rollback",
                            SCOPE,
                            Map.of("service", SERVICE, "target_revision", prevRev),
                            null));
        }
    }

    @Override
    public String toString() {
        return slug();
    }
}

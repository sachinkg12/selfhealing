package com.sachingupta.selfhealing.scenarios;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.advisor.evidence.EvidenceLedger;
import com.sachingupta.selfhealing.agent.Agent;
import com.sachingupta.selfhealing.agent.PlannedStep;
import com.sachingupta.selfhealing.agent.Planner;
import com.sachingupta.selfhealing.agent.ReasoningPhase;
import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.mcp.servers.DeployMcpServer;
import com.sachingupta.selfhealing.mcp.servers.LogFakeBackend;
import com.sachingupta.selfhealing.mcp.servers.LogMcpServer;
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
import org.springframework.stereotype.Component;

/**
 * Scenario 2: same loop as the happy path, but the Log MCP times out. Completeness drops, the
 * threshold rule fires, and the agent emits a refusal verdict with the missing signal listed.
 */
@Component
public class RefusalScenario implements Scenario {

    private static final Logger log = LoggerFactory.getLogger(RefusalScenario.class);

    private static final String SERVICE = "search-api";
    private static final String SCOPE = "search";
    private static final String INCIDENT = "INC-2026-0515-0002";

    private final Agent agent;
    private final ObjectProvider<PrometheusFakeBackend> prometheusFakeProvider;
    private final ObjectProvider<LogFakeBackend> logFakeProvider;
    private final PrincipalAclResolver aclResolver;

    public RefusalScenario(
            Agent agent,
            ObjectProvider<PrometheusFakeBackend> prometheusFakeProvider,
            ObjectProvider<LogFakeBackend> logFakeProvider,
            PrincipalAclResolver aclResolver) {
        this.agent = agent;
        this.prometheusFakeProvider = prometheusFakeProvider;
        this.logFakeProvider = logFakeProvider;
        this.aclResolver = aclResolver;
    }

    @Override
    public String slug() {
        return "refusal";
    }

    @Override
    public String title() {
        return "Log MCP timeout → refusal verdict with missing_signals";
    }

    @Override
    public String claim() {
        return "Refusal case: incomplete telemetry → REFUSE verdict with missing_signals";
    }

    @Override
    public void run() {
        AgentIdentity remediator = Identities.searchRemediator();
        aclResolver.install(
                remediator,
                new ToolAcl()
                        .permit("range_query", SCOPE)
                        .permit("history", SCOPE)
                        .permit("search", SCOPE));

        // Refusal scenario only meaningfully runs in fake-observability mode:
        // a real Loki always answers, so we cannot fake a timeout from the
        // agent's perspective without scaling the Loki pod to zero replicas
        // (out of scope for this scenario). When fakes are absent, run a
        // best-effort variant that still exercises the LogFakeBackend timeout
        // path if available; otherwise skip the timeout flip and rely on
        // whatever the real backend returns.
        PrometheusFakeBackend prometheusFake = prometheusFakeProvider.getIfAvailable();
        if (prometheusFake != null) {
            prometheusFake.setTrajectory(SCOPE, PrometheusFakeBackend.Trajectory.SPIKE);
        }
        LogFakeBackend logFake = logFakeProvider.getIfAvailable();
        if (logFake != null) {
            logFake.setMode(SCOPE, LogFakeBackend.Mode.TIMEOUT);
        } else {
            log.info(
                    "real-observability mode: refusal scenario cannot fake a Loki timeout in-band;"
                            + " agent will see the real cluster state instead");
        }

        ReasoningContext context =
                new ReasoningContext(INCIDENT, remediator, new EvidenceLedger())
                        .expect("range_query")
                        .expect("history")
                        .expect("search");
        context.verdict().hypothesis("post-deploy regression on search-api");

        Verdict verdict = agent.run(context, new RefuseOnTimeoutPlanner(remediator));
        log.info("");
        log.info("Verdict (refusal expected):\n{}", verdict.toJson());
    }

    private record RefuseOnTimeoutPlanner(AgentIdentity identity) implements Planner {

        @Override
        public List<PlannedStep> initialPlan(ReasoningContext context) {
            return List.of(
                    new PlannedStep(
                            ReasoningPhase.DETECT,
                            PrometheusMcpServer.NAME,
                            McpToolInvocation.read(
                                    identity, "range_query", SCOPE, Map.of("service", SERVICE))),
                    new PlannedStep(
                            ReasoningPhase.CORRELATE,
                            DeployMcpServer.NAME,
                            McpToolInvocation.read(
                                    identity, "history", SCOPE, Map.of("service", SERVICE))),
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
            return Optional.empty();
        }
    }
}

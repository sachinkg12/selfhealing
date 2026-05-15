package com.sachingupta.selfhealing.advisor.api;

import com.sachingupta.selfhealing.advisor.evidence.EvidenceLedger;
import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import com.sachingupta.selfhealing.verdict.Action;
import com.sachingupta.selfhealing.verdict.VerdictBuilder;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable context threaded through the advisor chain. Holds the evidence ledger, the set of
 * expected signals, the partial verdict builder, and the calling identity. Encapsulates everything
 * an advisor may read or contribute to.
 */
public final class ReasoningContext {

    private final String incidentId;
    private final AgentIdentity identity;
    private final EvidenceLedger ledger;
    private final Set<String> expectedSignals = new HashSet<>();
    private final VerdictBuilder verdict;
    private double computedConfidence;
    private boolean refused;
    private Action lastWriteAction;
    private Action proposedAction;
    private String humanApprovedToken;

    public ReasoningContext(String incidentId, AgentIdentity identity, EvidenceLedger ledger) {
        this.incidentId = incidentId;
        this.identity = identity;
        this.ledger = ledger;
        this.verdict = new VerdictBuilder().incidentId(incidentId);
    }

    public String incidentId() {
        return incidentId;
    }

    public AgentIdentity identity() {
        return identity;
    }

    public EvidenceLedger ledger() {
        return ledger;
    }

    public Set<String> expectedSignals() {
        return expectedSignals;
    }

    public ReasoningContext expect(String signal) {
        expectedSignals.add(signal);
        return this;
    }

    public VerdictBuilder verdict() {
        return verdict;
    }

    public double computedConfidence() {
        return computedConfidence;
    }

    public void setComputedConfidence(double computedConfidence) {
        this.computedConfidence = computedConfidence;
    }

    public boolean refused() {
        return refused;
    }

    public void markRefused() {
        this.refused = true;
    }

    public Optional<Action> lastWriteAction() {
        return Optional.ofNullable(lastWriteAction);
    }

    public void recordWriteAction(Action action) {
        this.lastWriteAction = action;
    }

    /**
     * Action the planner is about to execute. Set by the agent immediately before it gates the
     * pipeline (so the {@link com.sachingupta.selfhealing.advisor.refusal.RefusalPolicy} can
     * include it on a remediate verdict). Empty when no write step has been proposed yet.
     */
    public Optional<Action> proposedAction() {
        return Optional.ofNullable(proposedAction);
    }

    public void setProposedAction(Action proposedAction) {
        this.proposedAction = proposedAction;
    }

    /**
     * OAuth token minted by the human-approval gate, if one has been issued for the current
     * incident. The agent substitutes it into the write invocation just before calling the MCP
     * server.
     */
    public Optional<String> humanApprovedToken() {
        return Optional.ofNullable(humanApprovedToken);
    }

    public void setHumanApprovedToken(String humanApprovedToken) {
        this.humanApprovedToken = humanApprovedToken;
    }
}

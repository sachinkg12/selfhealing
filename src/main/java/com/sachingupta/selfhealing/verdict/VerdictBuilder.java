package com.sachingupta.selfhealing.verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for {@link Verdict}. Encapsulates the conditional shape rules (remediate vs refuse) so
 * callers cannot construct an inconsistent verdict.
 */
public final class VerdictBuilder {

    private String incidentId;
    private Decision decision;
    private String hypothesis = "";
    private final List<EvidenceRef> evidence = new ArrayList<>();
    private final List<String> missingSignals = new ArrayList<>();
    private double confidence;
    private double threshold;
    private Action action;
    private final List<String> nextSteps = new ArrayList<>();

    public VerdictBuilder incidentId(String incidentId) {
        this.incidentId = incidentId;
        return this;
    }

    public VerdictBuilder decision(Decision decision) {
        this.decision = decision;
        return this;
    }

    public VerdictBuilder hypothesis(String hypothesis) {
        this.hypothesis = hypothesis;
        return this;
    }

    public VerdictBuilder addEvidence(EvidenceRef ref) {
        this.evidence.add(ref);
        return this;
    }

    public VerdictBuilder addEvidence(String tool, String signal) {
        return addEvidence(new EvidenceRef(tool, signal));
    }

    public VerdictBuilder addMissingSignal(String spec) {
        this.missingSignals.add(spec);
        return this;
    }

    public VerdictBuilder confidence(double confidence) {
        // Two-decimal precision is the contract: the verdict is a human-readable JSON document
        // and raw IEEE-754 doubles (e.g. 0.6666666666666666) are noise. Keep the rounding here so
        // every confidence-emitting code path benefits.
        this.confidence = Math.round(confidence * 100.0) / 100.0;
        return this;
    }

    public VerdictBuilder threshold(double threshold) {
        this.threshold = threshold;
        return this;
    }

    public VerdictBuilder action(Action action) {
        this.action = action;
        return this;
    }

    public VerdictBuilder addNextStep(String step) {
        this.nextSteps.add(step);
        return this;
    }

    /**
     * Read-only accessors so collaborators (e.g. the human-approval gate) can render a snapshot of
     * the partial verdict without forcing a {@link #build()} that may throw before the verdict is
     * complete.
     */
    public String currentHypothesis() {
        return hypothesis;
    }

    public double currentThreshold() {
        return threshold;
    }

    public List<EvidenceRef> currentEvidence() {
        return List.copyOf(evidence);
    }

    public List<String> currentMissingSignals() {
        return List.copyOf(missingSignals);
    }

    public Verdict build() {
        if (incidentId == null || incidentId.isBlank()) {
            throw new IllegalStateException("verdict requires incidentId");
        }
        if (decision == null) {
            throw new IllegalStateException("verdict requires a decision");
        }
        if (decision == Decision.REMEDIATE && action == null) {
            throw new IllegalStateException("remediate verdict requires an action block");
        }
        if (decision == Decision.REFUSE && missingSignals.isEmpty()) {
            throw new IllegalStateException("refuse verdict requires at least one missing signal");
        }
        return new Verdict(
                incidentId,
                decision,
                hypothesis,
                List.copyOf(evidence),
                List.copyOf(missingSignals),
                confidence,
                threshold,
                action,
                List.copyOf(nextSteps));
    }
}

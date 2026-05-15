package com.sachingupta.selfhealing.advisor.confidence;

import com.sachingupta.selfhealing.advisor.api.Advisor;
import com.sachingupta.selfhealing.advisor.api.AdvisorChain;
import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import org.springframework.stereotype.Component;

/**
 * Advisor that computes the candidate action's confidence using the injected {@link
 * ConfidenceStrategy}. Runs after the {@link
 * com.sachingupta.selfhealing.advisor.evidence.EvidenceTracker} so the ledger is fully populated.
 */
@Component
public class ConfidenceScorer implements Advisor {

    private final ConfidenceStrategy strategy;

    public ConfidenceScorer(ConfidenceStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public String name() {
        return "confidence-scorer";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void advise(ReasoningContext context, AdvisorChain chain) {
        double score = strategy.score(context);
        context.setComputedConfidence(score);
        context.verdict().confidence(score);
        chain.proceed(context);
    }
}

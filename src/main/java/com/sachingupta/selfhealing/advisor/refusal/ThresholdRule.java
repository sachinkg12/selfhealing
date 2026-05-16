package com.sachingupta.selfhealing.advisor.refusal;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Refuse if computed confidence falls below a configurable threshold. */
@Component
public class ThresholdRule implements RefusalRule {

    private final double threshold;

    public ThresholdRule(@Value("${selfhealing.refusal.threshold:0.80}") double threshold) {
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }

    @Override
    public String name() {
        return "threshold-rule";
    }

    /**
     * Triggers a refusal when {@link ReasoningContext#computedConfidence()} drops below the
     * configured threshold, but adds nothing to {@code missing_signals}: that field is reserved for
     * missing inputs (which {@link CompletenessRule} populates). The threshold and confidence
     * values themselves are already part of the verdict envelope and carry the rationale.
     */
    @Override
    public Optional<List<String>> evaluate(ReasoningContext context) {
        if (context.computedConfidence() < threshold) {
            return Optional.of(List.of());
        }
        return Optional.empty();
    }
}

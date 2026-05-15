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

    @Override
    public Optional<List<String>> evaluate(ReasoningContext context) {
        if (context.computedConfidence() < threshold) {
            return Optional.of(
                    List.of(
                            "confidence "
                                    + context.computedConfidence()
                                    + " below threshold "
                                    + threshold));
        }
        return Optional.empty();
    }
}

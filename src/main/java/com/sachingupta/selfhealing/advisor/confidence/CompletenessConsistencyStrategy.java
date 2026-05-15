package com.sachingupta.selfhealing.advisor.confidence;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.verdict.McpResponse;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Default {@link ConfidenceStrategy}. Computes a score in [0, 1] over the expected read signals:
 *
 * <ul>
 *   <li>completeness = (retrieved expected signals) / (total expected signals)
 *   <li>consistency = 1.0 when all expected reads returned a non-empty {@code signal}; otherwise
 *       proportional to retrieval.
 * </ul>
 *
 * <p>The weighted sum is {@code 0.6 × completeness + 0.4 × consistency}. The strategy treats
 * "consistency" as the binary "did the data come back coherently"; richer NLP-style cross-signal
 * comparison is a future replacement plugged in by swapping the Spring bean.
 */
@Component
public class CompletenessConsistencyStrategy implements ConfidenceStrategy {

    private final double completenessWeight = 0.6;
    private final double consistencyWeight = 0.4;

    @Override
    public double score(ReasoningContext context) {
        double completeness = completeness(context);
        double consistency = consistency(context);
        return clamp(completenessWeight * completeness + consistencyWeight * consistency);
    }

    private double completeness(ReasoningContext context) {
        Set<String> expected = context.expectedSignals();
        if (expected.isEmpty()) {
            return 1.0;
        }
        long retrieved =
                context.ledger().entries().stream()
                        .filter(McpResponse::ok)
                        .filter(r -> expected.contains(r.tool()))
                        .distinct()
                        .map(McpResponse::tool)
                        .distinct()
                        .count();
        return Math.min(1.0, retrieved / (double) expected.size());
    }

    private double consistency(ReasoningContext context) {
        Set<String> expected = context.expectedSignals();
        if (expected.isEmpty()) {
            return 1.0;
        }
        long retrieved =
                context.ledger().entries().stream()
                        .filter(McpResponse::ok)
                        .filter(r -> expected.contains(r.tool()))
                        .map(McpResponse::tool)
                        .distinct()
                        .count();
        if (retrieved < expected.size()) {
            // Missing data; consistency proportional to retrieval.
            return retrieved / (double) expected.size();
        }
        boolean allCoherent =
                context.ledger().entries().stream()
                        .filter(McpResponse::ok)
                        .filter(r -> expected.contains(r.tool()))
                        .allMatch(r -> r.signal() != null && !r.signal().isBlank());
        return allCoherent ? 1.0 : 0.5;
    }

    private static double clamp(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }
}

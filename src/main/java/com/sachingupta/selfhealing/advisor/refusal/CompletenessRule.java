package com.sachingupta.selfhealing.advisor.refusal;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Specification: refuse if any expected signal was not retrieved. Produces the {@code
 * missing_signals} field of the refusal verdict in spec-style format (e.g. {@code log.search(rev=
 * current, window=[T-12m,T-0])}).
 */
@Component
public class CompletenessRule implements RefusalRule {

    @Override
    public String name() {
        return "completeness-rule";
    }

    @Override
    public Optional<List<String>> evaluate(ReasoningContext context) {
        List<String> missing = new ArrayList<>();
        for (String expected : context.expectedSignals()) {
            boolean retrieved =
                    context.ledger().entries().stream()
                            .filter(r -> r.ok())
                            .anyMatch(r -> r.tool().equals(expected));
            if (!retrieved) {
                missing.add(describe(expected));
            }
        }
        return missing.isEmpty() ? Optional.empty() : Optional.of(missing);
    }

    /**
     * Translate a bare expected-tool name into the spec-style missing-signal string. Falls back to
     * the bare name for unknown tools so the rule never silently swallows a missing signal.
     */
    private static String describe(String toolName) {
        return switch (toolName) {
            case "range_query" -> "prometheus.range_query(metric=p99, window=[T-30m,T-0])";
            case "instant_query" -> "prometheus.instant_query(metric=p99)";
            case "alert_state" -> "prometheus.alert_state()";
            case "history" -> "deploy.history(window=[T-1h,T-0])";
            case "current_revision" -> "deploy.current_revision()";
            case "search" -> "log.search(rev=current, window=[T-12m,T-0])";
            default -> toolName;
        };
    }
}

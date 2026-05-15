package com.sachingupta.selfhealing.verdict;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;

/**
 * Structured verdict emitted at the end of an agent run. The wire schema uses snake_case keys, a
 * lowercase decision, an action block when remediating, and a missing_signals list when refusing.
 *
 * <p>Construct via {@link VerdictBuilder} (Builder pattern) so decision-specific invariants are
 * enforced at construction time.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Verdict(
        String incidentId,
        Decision decision,
        String hypothesis,
        List<EvidenceRef> evidence,
        List<String> missingSignals,
        double confidence,
        double threshold,
        Action action,
        List<String> nextSteps) {

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("verdict JSON serialization failed", e);
        }
    }
}

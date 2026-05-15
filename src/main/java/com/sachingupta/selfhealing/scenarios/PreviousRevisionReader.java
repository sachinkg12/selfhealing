package com.sachingupta.selfhealing.scenarios;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.verdict.McpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the "previous revision" from the latest {@code history} response in the evidence ledger.
 * Used by deterministic planners so the rollback target is discovered from the real backend rather
 * than hardcoded — keeps scenarios backend-agnostic.
 *
 * <p>Convention: the most recent rollout (last entry, marked {@code kind=canary}) is the current
 * revision; the entry immediately before it is the rollback target.
 */
final class PreviousRevisionReader {

    private PreviousRevisionReader() {}

    static Optional<String> read(ReasoningContext context) {
        return context.ledger().latestFor("history").flatMap(PreviousRevisionReader::extract);
    }

    private static Optional<String> extract(McpResponse historyResponse) {
        Object payload = historyResponse.payload();
        if (!(payload instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Object rolloutsObj = map.get("rollouts");
        if (!(rolloutsObj instanceof List<?> rollouts) || rollouts.size() < 2) {
            return Optional.empty();
        }
        Object prior = rollouts.get(rollouts.size() - 2);
        if (!(prior instanceof Map<?, ?> priorMap)) {
            return Optional.empty();
        }
        Object rev = priorMap.get("revision");
        return rev == null ? Optional.empty() : Optional.of(rev.toString());
    }
}

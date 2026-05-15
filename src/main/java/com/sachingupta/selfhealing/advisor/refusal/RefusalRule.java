package com.sachingupta.selfhealing.advisor.refusal;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import java.util.List;
import java.util.Optional;

/**
 * Specification (Specification pattern). A rule returns the list of missing-signal strings that
 * justify refusal, or empty if the rule is satisfied. Composable: the policy refuses if any rule
 * produces a non-empty result.
 */
public interface RefusalRule {

    String name();

    Optional<List<String>> evaluate(ReasoningContext context);
}

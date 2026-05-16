package com.sachingupta.selfhealing.advisor.refusal;

import com.sachingupta.selfhealing.advisor.api.Advisor;
import com.sachingupta.selfhealing.advisor.api.AdvisorChain;
import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.verdict.Decision;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Advisor that composes {@link RefusalRule}s. Runs last in the chain. If any rule produces
 * missing-signal output, the verdict is flipped to {@link Decision#REFUSE} and the union of all
 * missing signals is attached.
 */
@Component
public class RefusalPolicy implements Advisor {

    private final List<RefusalRule> rules;
    private final ThresholdRule thresholdRule;

    public RefusalPolicy(List<RefusalRule> rules, ThresholdRule thresholdRule) {
        this.rules = List.copyOf(rules);
        this.thresholdRule = thresholdRule;
    }

    @Override
    public String name() {
        return "refusal-policy";
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public void advise(ReasoningContext context, AdvisorChain chain) {
        context.verdict().threshold(thresholdRule.threshold());

        boolean refusing = false;
        for (RefusalRule rule : rules) {
            var missing = rule.evaluate(context);
            if (missing.isPresent()) {
                refusing = true;
                missing.get().forEach(context.verdict()::addMissingSignal);
            }
        }
        if (refusing) {
            context.markRefused();
            context.verdict().decision(Decision.REFUSE);
            // Derive a "retry <tool>" next-step per missing input so the human reading the verdict
            // sees the specific action that would unblock the agent. Fall back to a generic
            // escalation step so on-call still has an obvious second route.
            for (var ref : context.verdict().currentMissingSignals()) {
                String hint = retryHintFor(ref);
                if (hint != null) {
                    context.verdict().addNextStep(hint);
                }
            }
            context.verdict().addNextStep("escalate to on-call human");
        } else {
            context.verdict().decision(Decision.REMEDIATE);
            // Prefer the action the planner has proposed for the upcoming write step (set by
            // the agent before it gates the pipeline). Fall back to the most recent executed
            // write action when the verdict is computed post-hoc — that path applies to
            // read-only runs and to scenarios that never proposed a write.
            context.proposedAction()
                    .or(context::lastWriteAction)
                    .ifPresent(context.verdict()::action);
        }
        chain.proceed(context);
    }

    /**
     * Extracts a "retry &lt;tool&gt;" next-step from a {@code missing_signals} entry. A spec-style
     * entry like {@code log.search(rev=current, window=[T-12m,T-0])} maps to {@code retry
     * log.search}. Returns {@code null} for entries we can't parse, so the caller skips them.
     */
    private static String retryHintFor(String missingSignal) {
        if (missingSignal == null || missingSignal.isBlank()) {
            return null;
        }
        int paren = missingSignal.indexOf('(');
        String tool = paren > 0 ? missingSignal.substring(0, paren) : missingSignal;
        tool = tool.trim();
        return tool.isEmpty() ? null : "retry " + tool;
    }
}

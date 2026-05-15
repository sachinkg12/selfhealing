package com.sachingupta.selfhealing.advisor.evidence;

import com.sachingupta.selfhealing.advisor.api.Advisor;
import com.sachingupta.selfhealing.advisor.api.AdvisorChain;
import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.verdict.McpResponse;
import org.springframework.stereotype.Component;

/**
 * Advisor (Decorator over the agent loop) that copies the contents of the per-incident ledger into
 * the verdict's evidence list. Tool names are emitted as {@code server.tool} (e.g. {@code
 * prometheus.range_query}) so the verdict's wire format is server-qualified.
 *
 * <p>Runs early in the chain so later advisors see a fully attributed evidence record.
 */
@Component
public class EvidenceTracker implements Advisor {

    @Override
    public String name() {
        return "evidence-tracker";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void advise(ReasoningContext context, AdvisorChain chain) {
        for (McpResponse r : context.ledger().entries()) {
            if (!r.ok()) {
                continue;
            }
            String signal = r.signal() == null || r.signal().isBlank() ? "ok" : r.signal();
            context.verdict().addEvidence(r.qualifiedTool(), signal);
        }
        chain.proceed(context);
    }
}

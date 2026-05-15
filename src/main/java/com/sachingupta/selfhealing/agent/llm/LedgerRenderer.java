package com.sachingupta.selfhealing.agent.llm;

import com.sachingupta.selfhealing.advisor.evidence.EvidenceLedger;
import com.sachingupta.selfhealing.verdict.McpResponse;
import org.springframework.stereotype.Component;

/**
 * Renders the per-incident evidence ledger as text for the LLM prompt. Separated from {@link
 * LlmPlanner} so prompt formatting changes don't ripple through planner logic (SRP).
 */
@Component
public class LedgerRenderer {

    public String render(EvidenceLedger ledger) {
        if (ledger.size() == 0) {
            return "(empty — no tool calls yet)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (McpResponse r : ledger.entries()) {
            sb.append('[').append(i++).append("] ").append(r.tool());
            if (r.ok()) {
                sb.append(" → ").append(r.signal());
            } else {
                sb.append(" → FAILED: ").append(r.error());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}

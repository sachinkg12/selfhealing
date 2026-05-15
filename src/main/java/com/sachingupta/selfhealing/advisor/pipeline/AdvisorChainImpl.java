package com.sachingupta.selfhealing.advisor.pipeline;

import com.sachingupta.selfhealing.advisor.api.Advisor;
import com.sachingupta.selfhealing.advisor.api.AdvisorChain;
import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import java.util.List;

/**
 * Walks the advisor list one position at a time. Stateless beyond the cursor, so the same pipeline
 * can run concurrently for many incidents.
 */
final class AdvisorChainImpl implements AdvisorChain {

    private final List<Advisor> advisors;
    private final int cursor;

    AdvisorChainImpl(List<Advisor> advisors, int cursor) {
        this.advisors = advisors;
        this.cursor = cursor;
    }

    @Override
    public void proceed(ReasoningContext context) {
        if (cursor >= advisors.size()) {
            return;
        }
        Advisor next = advisors.get(cursor);
        next.advise(context, new AdvisorChainImpl(advisors, cursor + 1));
    }
}

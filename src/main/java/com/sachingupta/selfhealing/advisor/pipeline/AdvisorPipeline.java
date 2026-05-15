package com.sachingupta.selfhealing.advisor.pipeline;

import com.sachingupta.selfhealing.advisor.api.Advisor;
import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Composes a list of advisors into a chain (Chain of Responsibility). Pulled together by Spring's
 * component scan; new advisors are added by registering a new {@link Advisor} bean (Open/Closed).
 */
@Component
public class AdvisorPipeline {

    private final List<Advisor> ordered;

    public AdvisorPipeline(List<Advisor> advisors) {
        this.ordered = advisors.stream().sorted(Comparator.comparingInt(Advisor::order)).toList();
    }

    public List<Advisor> ordered() {
        return ordered;
    }

    public void run(ReasoningContext context) {
        new AdvisorChainImpl(ordered, 0).proceed(context);
    }
}

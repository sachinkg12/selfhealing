package com.sachingupta.selfhealing.advisor.api;

/**
 * One link in the chain. Modeled on Spring AI's {@code CallAroundAdvisor} shape: each advisor
 * inspects/decorates the call, then either passes control to the next advisor in the chain or
 * short-circuits.
 *
 * <p>{@link #order()} controls the chain position; lower runs earlier.
 */
public interface Advisor {

    String name();

    int order();

    void advise(ReasoningContext context, AdvisorChain chain);
}

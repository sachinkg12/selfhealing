package com.sachingupta.selfhealing.advisor.api;

/**
 * Chain handle passed to each advisor. Calling {@link #proceed} hands control to the next advisor;
 * not calling it short-circuits the chain.
 */
public interface AdvisorChain {

    void proceed(ReasoningContext context);
}

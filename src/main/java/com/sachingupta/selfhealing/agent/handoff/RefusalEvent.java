package com.sachingupta.selfhealing.agent.handoff;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;
import com.sachingupta.selfhealing.verdict.Verdict;

/**
 * Application event fired by the {@link com.sachingupta.selfhealing.agent.Agent} immediately after
 * producing a verdict whose decision is {@code REFUSE}. Hand-off listeners subscribe via {@link
 * org.springframework.context.event.EventListener}; adding a new target (Slack, future PagerDuty,
 * Opsgenie, ServiceNow, …) is a pure addition (Open/Closed) — neither the agent nor any existing
 * listener needs to change.
 *
 * <p>Carries both the immutable {@link Verdict} (the structured hand-off artifact) and the live
 * {@link ReasoningContext} so listeners can read the identity, expected signals, and full evidence
 * ledger if they want richer content than the verdict alone carries.
 */
public record RefusalEvent(Verdict verdict, ReasoningContext context) {}

package com.sachingupta.selfhealing.agent.humangate;

/**
 * Mediator (Mediator pattern) between the agent and whoever — a human, an auto-approver in CI, or
 * an interactive Slack reviewer — authorises a destructive write. Implementations are responsible
 * for minting the OAuth token when approval is granted; the agent does not know which token issuer
 * is in play.
 */
public interface HumanApprovalGate {

    HumanApprovalOutcome requestApproval(HumanApprovalRequest request);
}

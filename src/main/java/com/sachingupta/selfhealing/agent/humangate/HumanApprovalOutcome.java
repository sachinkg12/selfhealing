package com.sachingupta.selfhealing.agent.humangate;

/**
 * Result of a human-approval gate. When {@link #approved()} is true, {@link #wireToken()} is a
 * freshly-minted, audience-scoped, short-lived OAuth token the agent passes to the write tool; when
 * false, the reason is captured in {@link #reason()} so the refusal verdict can cite it.
 */
public record HumanApprovalOutcome(
        boolean approved, String wireToken, String approver, String reason) {

    public static HumanApprovalOutcome approved(String wireToken, String approver) {
        return new HumanApprovalOutcome(true, wireToken, approver, "approved");
    }

    public static HumanApprovalOutcome denied(String approver, String reason) {
        return new HumanApprovalOutcome(false, null, approver, reason);
    }

    public static HumanApprovalOutcome timeout(long seconds) {
        return new HumanApprovalOutcome(
                false, null, "(no responder)", "no response within " + seconds + "s");
    }
}

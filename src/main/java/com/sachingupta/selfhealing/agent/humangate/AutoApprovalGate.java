package com.sachingupta.selfhealing.agent.humangate;

import com.sachingupta.selfhealing.security.oauth.ApprovalToken;
import com.sachingupta.selfhealing.security.oauth.TokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link HumanApprovalGate} for unattended runs (CI, local sanity checks). Approves every
 * request and mints the OAuth token in the name of a synthetic {@code auto-approval} subject. Logs
 * each decision so the run log clearly documents that no human reviewed the action.
 *
 * <p>Active when {@code selfhealing.human-gate.mode} is missing or {@code auto}.
 */
@Component
@ConditionalOnProperty(
        name = "selfhealing.human-gate.mode",
        havingValue = "auto",
        matchIfMissing = true)
public class AutoApprovalGate implements HumanApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(AutoApprovalGate.class);

    private final TokenIssuer tokenIssuer;

    public AutoApprovalGate(TokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    public HumanApprovalOutcome requestApproval(HumanApprovalRequest request) {
        log.info(
                "(auto-approval) approving {} for incident {} (confidence={}, threshold={})",
                request.proposedTool(),
                request.incidentId(),
                request.confidence(),
                request.threshold());
        ApprovalToken token =
                tokenIssuer.mint(
                        "auto-approval@selfhealing", request.audience(), request.scope(), 60);
        return HumanApprovalOutcome.approved(tokenIssuer.issue(token), "auto-approval");
    }
}

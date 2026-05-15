package com.sachingupta.selfhealing.agent.humangate;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;

import com.sachingupta.selfhealing.verdict.EvidenceRef;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.ButtonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the human-approval message as a Slack Block Kit layout. The shape is deliberately verbose
 * so the on-call reviewer can decide without leaving Slack: incident, hypothesis, confidence, every
 * retrieved evidence signal, the proposed write, what approval mints, and what happens on timeout.
 */
final class SlackBlockKit {

    static final String ACTION_APPROVE = "selfhealing_approve";
    static final String ACTION_DENY = "selfhealing_deny";

    private SlackBlockKit() {}

    static List<LayoutBlock> approvalMessage(HumanApprovalRequest req, long timeoutSeconds) {
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(
                header(
                        h ->
                                h.text(
                                        plainText(
                                                ":rotating_light: Agentic Ops Gateway — Approval Required",
                                                true))));

        blocks.add(
                section(
                        s ->
                                s.fields(
                                        List.of(
                                                markdownText(
                                                        "*Incident*\n`" + req.incidentId() + "`"),
                                                markdownText("*Scope*\n`" + req.scope() + "`")))));

        blocks.add(
                section(
                        s ->
                                s.text(
                                        markdownText(
                                                "*Hypothesis*\n"
                                                        + req.hypothesis()
                                                        + "\n*Confidence:* `"
                                                        + formatConfidence(req.confidence())
                                                        + "` (threshold `"
                                                        + formatConfidence(req.threshold())
                                                        + "`)"))));

        blocks.add(divider());

        StringBuilder evidence = new StringBuilder("*Evidence gathered*\n");
        for (EvidenceRef ref : req.evidence()) {
            evidence.append("• `")
                    .append(ref.tool())
                    .append("` — ")
                    .append(ref.signal())
                    .append('\n');
        }
        blocks.add(section(s -> s.text(markdownText(evidence.toString().trim()))));

        StringBuilder action = new StringBuilder();
        action.append("*Proposed remediation*\n`").append(req.proposedTool()).append("`\n");
        for (Map.Entry<String, Object> e : req.arguments().entrySet()) {
            action.append("• ")
                    .append(e.getKey())
                    .append(" = `")
                    .append(e.getValue())
                    .append("`\n");
        }
        blocks.add(section(s -> s.text(markdownText(action.toString().trim()))));

        blocks.add(divider());

        blocks.add(
                section(
                        s ->
                                s.text(
                                        markdownText(
                                                "*What your decision does*\n"
                                                        + "• *Approve* mints a 60-second"
                                                        + " RS256 JWT scoped to audience `"
                                                        + req.audience()
                                                        + "` and scope `"
                                                        + req.scope()
                                                        + "`, then runs the write tool exactly"
                                                        + " once.\n"
                                                        + "• *Deny* emits a refusal verdict and"
                                                        + " stops; no write is invoked.\n"
                                                        + "• If no one responds within "
                                                        + timeoutSeconds
                                                        + "s the gate times out and the agent"
                                                        + " refuses the run."))));

        ButtonElement approve =
                ButtonElement.builder()
                        .text(plainText(":white_check_mark: Approve", true))
                        .style("primary")
                        .actionId(ACTION_APPROVE)
                        .value(req.incidentId())
                        .build();
        ButtonElement deny =
                ButtonElement.builder()
                        .text(plainText(":x: Deny", true))
                        .style("danger")
                        .actionId(ACTION_DENY)
                        .value(req.incidentId())
                        .build();
        blocks.add(actions(a -> a.elements(List.of(approve, deny))));
        return blocks;
    }

    private static String formatConfidence(double v) {
        return String.format("%.2f", v);
    }
}

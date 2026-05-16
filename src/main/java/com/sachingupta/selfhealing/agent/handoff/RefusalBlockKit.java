package com.sachingupta.selfhealing.agent.handoff;

import com.sachingupta.selfhealing.verdict.EvidenceRef;
import com.sachingupta.selfhealing.verdict.Verdict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a refusal hand-off as a Slack Block Kit message payload. Distinguished from the approval
 * Block Kit by a different header and the absence of Approve/Deny buttons: this is informational
 * hand-off to the on-call rotation, not a request for them to decide.
 *
 * <p>Returns a {@link Map} suitable for {@code ObjectMapper.writeValueAsString(...)} — top-level
 * {@code text} (fallback for clients that cannot render blocks), {@code blocks} (Block Kit layout
 * with mention, header, evidence, missing signals, suggested next steps, footer). The caller owns
 * the HTTP POST.
 */
public final class RefusalBlockKit {

    private RefusalBlockKit() {}

    public static Map<String, Object> payload(Verdict verdict, String mention) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (mention != null && !mention.isBlank()) {
            blocks.add(section(mention));
        }
        blocks.add(
                header(":rotating_light: Agentic Ops Gateway — REFUSED, on-call action required"));
        blocks.add(divider());

        blocks.add(
                section(
                        "*Incident*\n`"
                                + verdict.incidentId()
                                + "`\n*Hypothesis*\n"
                                + nullSafe(verdict.hypothesis())
                                + "\n*Confidence:* `"
                                + format(verdict.confidence())
                                + "`  (threshold `"
                                + format(verdict.threshold())
                                + "`) :x: below threshold"));

        blocks.add(divider());

        int evidenceCount = verdict.evidence() == null ? 0 : verdict.evidence().size();
        StringBuilder evidence =
                new StringBuilder("*Evidence gathered (").append(evidenceCount).append(")*\n");
        if (evidenceCount == 0) {
            evidence.append("_(none retrieved)_");
        } else {
            for (EvidenceRef ref : verdict.evidence()) {
                evidence.append("• `")
                        .append(ref.tool())
                        .append("` — ")
                        .append(nullSafe(ref.signal()))
                        .append('\n');
            }
        }
        blocks.add(section(evidence.toString().trim()));

        if (verdict.missingSignals() != null && !verdict.missingSignals().isEmpty()) {
            StringBuilder missing = new StringBuilder("*Missing signals*\n");
            for (String s : verdict.missingSignals()) {
                missing.append("• `").append(s).append("`\n");
            }
            blocks.add(section(missing.toString().trim()));
        }

        if (verdict.nextSteps() != null && !verdict.nextSteps().isEmpty()) {
            StringBuilder steps = new StringBuilder("*Suggested next steps*\n");
            for (String s : verdict.nextSteps()) {
                steps.append("• ").append(s).append('\n');
            }
            blocks.add(section(steps.toString().trim()));
        }

        blocks.add(divider());

        blocks.add(
                section(
                        "_No write executed. The agent has stopped and is handing this off to"
                                + " you._"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "Agentic Ops Gateway — REFUSED for " + verdict.incidentId());
        payload.put("blocks", blocks);
        return payload;
    }

    private static Map<String, Object> header(String text) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "header");
        b.put("text", Map.of("type", "plain_text", "text", text, "emoji", true));
        return b;
    }

    private static Map<String, Object> section(String markdown) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "section");
        b.put("text", Map.of("type", "mrkdwn", "text", markdown));
        return b;
    }

    private static Map<String, Object> divider() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "divider");
        return b;
    }

    private static String format(double v) {
        return String.format("%.2f", v);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}

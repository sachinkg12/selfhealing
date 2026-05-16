package com.sachingupta.selfhealing.agent.handoff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sachingupta.selfhealing.mcp.servers.SlackWebhookClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Posts a hand-off Block Kit message to the configured Slack webhook channel whenever the agent
 * emits a refuse verdict — covers both threshold-driven refusals and human-gate timeout/deny
 * refusals. Active only when {@code selfhealing.refusal.handoff.slack.enabled=true}.
 *
 * <p>Reuses the existing {@link SlackWebhookClient} (same {@code SLACK_WEBHOOK_URL} as the {@code
 * notification.post} tool) so no second webhook URL is required. The Block Kit has no Approve/Deny
 * buttons — this is informational hand-off, not a request for action.
 *
 * <p>Spring's event publication is synchronous; a Slack failure is logged but does not propagate,
 * so an outage cannot crash the agent run. Failures are isolated per listener.
 */
@Component
@ConditionalOnProperty(name = "selfhealing.refusal.handoff.slack.enabled", havingValue = "true")
public class SlackRefusalListener {

    private static final Logger log = LoggerFactory.getLogger(SlackRefusalListener.class);

    private final SlackWebhookClient client;
    private final String mention;
    private final ObjectMapper mapper = new ObjectMapper();

    public SlackRefusalListener(
            SlackWebhookClient client,
            @Value("${selfhealing.refusal.handoff.slack.mention:<!here>}") String mention) {
        this.client = client;
        this.mention = mention;
    }

    @EventListener
    public void onRefusal(RefusalEvent event) {
        Map<String, Object> payload = RefusalBlockKit.payload(event.verdict(), mention);
        try {
            String json = mapper.writeValueAsString(payload);
            SlackWebhookClient.PostResult result = client.postPayload(json);
            log.info(
                    "refusal hand-off posted to Slack (status={}, ok={}) for incident {}",
                    result.statusCode(),
                    result.ok(),
                    event.verdict().incidentId());
        } catch (JsonProcessingException e) {
            log.warn("refusal hand-off serialization failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("refusal hand-off post failed: {}", e.getMessage());
        }
    }
}

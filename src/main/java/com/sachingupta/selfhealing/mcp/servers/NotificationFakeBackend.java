package com.sachingupta.selfhealing.mcp.servers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Notification sink. Delegates to {@link SlackWebhookClient} when a webhook URL is configured;
 * otherwise records messages in-memory and logs them. The fake name is retained for backwards
 * compatibility with the rest of the codebase — the post-to-real-Slack path is wired through {@link
 * SlackWebhookClient}.
 */
@Component
public class NotificationFakeBackend {

    private static final Logger log = LoggerFactory.getLogger(NotificationFakeBackend.class);

    private final SlackWebhookClient slack;
    private final List<PostedMessage> posted = new ArrayList<>();

    public NotificationFakeBackend(SlackWebhookClient slack) {
        this.slack = slack;
    }

    public Map<String, Object> post(String scope, Map<String, Object> arguments) {
        String channel = (String) arguments.getOrDefault("channel", "#oncall");
        String body = (String) arguments.getOrDefault("body", "");
        PostedMessage m = new PostedMessage(channel, scope, body, Instant.now());
        posted.add(m);

        SlackWebhookClient.PostResult result = slack.post(channel, "[" + scope + "] " + body);
        log.info(
                "Slack post → channel={}, slack_enabled={}, status={}",
                channel,
                slack.enabled(),
                result.statusCode());

        return Map.of(
                "channel",
                channel,
                "service",
                scope,
                "ts",
                m.when().toString(),
                "delivered",
                result.ok(),
                "signal",
                slack.enabled()
                        ? "posted to " + channel + " (real Slack, http " + result.statusCode() + ")"
                        : "posted to " + channel + " (local stub)");
    }

    public Map<String, Object> threadReply(String scope, Map<String, Object> arguments) {
        return Map.of(
                "channel",
                arguments.getOrDefault("channel", "#oncall"),
                "service",
                scope,
                "appended",
                true,
                "signal",
                "ledger appended");
    }

    public List<PostedMessage> posted() {
        return List.copyOf(posted);
    }

    public record PostedMessage(String channel, String service, String body, Instant when) {}
}

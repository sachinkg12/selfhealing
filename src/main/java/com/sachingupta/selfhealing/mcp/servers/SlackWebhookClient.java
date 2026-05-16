package com.sachingupta.selfhealing.mcp.servers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adapter to a real Slack incoming webhook. If {@code SLACK_WEBHOOK_URL} is blank the client
 * returns a no-op response so scenarios still run end to end without a configured workspace. Kept
 * separate from {@link NotificationFakeBackend} so the wire call has its own SRP unit.
 */
@Component
public class SlackWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(SlackWebhookClient.class);

    private final String webhookUrl;
    private final HttpClient http;

    public SlackWebhookClient(@Value("${SLACK_WEBHOOK_URL:}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean enabled() {
        return !webhookUrl.isBlank();
    }

    public PostResult post(String channelHint, String body) {
        if (!enabled()) {
            log.info("(Slack disabled — printing locally) [{}] {}", channelHint, body);
            return new PostResult(true, 0, "stub");
        }
        return sendJson("{\"text\": " + jsonString(body) + "}");
    }

    /**
     * Posts a fully-formed JSON payload (e.g. a Slack Block Kit message with {@code text} and
     * {@code blocks} fields) to the webhook URL without any wrapping. Used by callers that need
     * richer formatting than the simple {@code {"text": "..."}} envelope produced by {@link
     * #post(String, String)}.
     */
    public PostResult postPayload(String fullJsonPayload) {
        if (!enabled()) {
            log.info("(Slack disabled — printing locally) {}", fullJsonPayload);
            return new PostResult(true, 0, "stub");
        }
        return sendJson(fullJsonPayload);
    }

    private PostResult sendJson(String payload) {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new PostResult(
                    response.statusCode() >= 200 && response.statusCode() < 300,
                    response.statusCode(),
                    response.body());
        } catch (Exception e) {
            log.warn("slack webhook send failed: {}", e.getMessage());
            return new PostResult(false, -1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public record PostResult(boolean ok, int statusCode, String body) {}
}

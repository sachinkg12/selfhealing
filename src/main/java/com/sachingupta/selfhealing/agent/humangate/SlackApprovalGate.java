package com.sachingupta.selfhealing.agent.humangate;

import com.sachingupta.selfhealing.security.oauth.ApprovalToken;
import com.sachingupta.selfhealing.security.oauth.TokenIssuer;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.socket_mode.SocketModeClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real Slack-driven {@link HumanApprovalGate}. Posts a Block Kit message to the configured channel
 * with Approve / Deny buttons, blocks the agent thread on a {@link CompletableFuture}, and resolves
 * it from the Bolt action handler when the on-call clicks. On approve, mints the OAuth token in the
 * clicker's name (the {@code sub} claim is the Slack user id) and returns it as the outcome.
 *
 * <p>Uses Slack <a href="https://api.slack.com/apis/connections/socket">Socket Mode</a>: the agent
 * opens an outbound WebSocket so no public callback URL is required. The Bolt {@code App} runs the
 * callback dispatch on a worker thread pool; the agent's main thread waits on a future keyed by
 * incident id.
 *
 * <p>Active when {@code selfhealing.human-gate.mode=slack}.
 */
@Component
@ConditionalOnProperty(name = "selfhealing.human-gate.mode", havingValue = "slack")
public class SlackApprovalGate implements HumanApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(SlackApprovalGate.class);

    private final String botToken;
    private final String appToken;
    private final String channel;
    private final long timeoutSeconds;
    private final TokenIssuer tokenIssuer;

    private App boltApp;
    private SocketModeApp socketApp;
    private MethodsClient slackClient;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public SlackApprovalGate(
            @Value("${SLACK_BOT_TOKEN}") String botToken,
            @Value("${SLACK_APP_TOKEN}") String appToken,
            @Value("${SLACK_APPROVAL_CHANNEL}") String channel,
            @Value("${selfhealing.human-gate.timeout-seconds:120}") long timeoutSeconds,
            TokenIssuer tokenIssuer) {
        this.botToken = botToken;
        this.appToken = appToken;
        this.channel = channel;
        this.timeoutSeconds = timeoutSeconds;
        this.tokenIssuer = tokenIssuer;
    }

    @PostConstruct
    void start() throws Exception {
        if (botToken == null || botToken.isBlank() || appToken == null || appToken.isBlank()) {
            throw new IllegalStateException(
                    "Slack approval gate requires SLACK_BOT_TOKEN and SLACK_APP_TOKEN; got bot='"
                            + (botToken == null ? "" : "<" + botToken.length() + " chars>")
                            + "' app='"
                            + (appToken == null ? "" : "<" + appToken.length() + " chars>")
                            + "'");
        }
        AppConfig config = AppConfig.builder().singleTeamBotToken(botToken).build();
        boltApp = new App(config);
        slackClient = boltApp.client();
        boltApp.blockAction(
                SlackBlockKit.ACTION_APPROVE,
                (req, ctx) -> {
                    completePending(
                            req.getPayload().getActions().get(0).getValue(), true, ctx, req);
                    return ctx.ack();
                });
        boltApp.blockAction(
                SlackBlockKit.ACTION_DENY,
                (req, ctx) -> {
                    completePending(
                            req.getPayload().getActions().get(0).getValue(), false, ctx, req);
                    return ctx.ack();
                });
        // JavaWebSocket backend uses org.java-websocket:Java-WebSocket (no javax.websocket
        // dependency, which Bolt 1.42's default Tyrus backend would otherwise require).
        socketApp = new SocketModeApp(appToken, SocketModeClient.Backend.JavaWebSocket, boltApp);
        socketApp.startAsync();
        log.info(
                "Slack approval gate ready (channel={}, timeout={}s, mode=Socket Mode)",
                channel,
                timeoutSeconds);
    }

    @PreDestroy
    void stop() {
        try {
            if (socketApp != null) {
                socketApp.close();
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Override
    public HumanApprovalOutcome requestApproval(HumanApprovalRequest request) {
        CompletableFuture<HumanApprovalOutcome> future = new CompletableFuture<>();
        pending.put(request.incidentId(), new Pending(request, future));

        String messageTs;
        try {
            ChatPostMessageResponse response =
                    slackClient.chatPostMessage(
                            b ->
                                    b.channel(channel)
                                            .text("Approval required for " + request.incidentId())
                                            .blocks(
                                                    SlackBlockKit.approvalMessage(
                                                            request, timeoutSeconds)));
            if (!response.isOk()) {
                pending.remove(request.incidentId());
                throw new IllegalStateException("chat.postMessage failed: " + response.getError());
            }
            messageTs = response.getTs();
            log.info(
                    "Slack approval posted to {} (ts={}) for incident {}; waiting up to {}s",
                    channel,
                    messageTs,
                    request.incidentId(),
                    timeoutSeconds);
        } catch (Exception e) {
            pending.remove(request.incidentId());
            throw new RuntimeException("Slack approval request failed: " + e.getMessage(), e);
        }

        try {
            HumanApprovalOutcome outcome = future.get(timeoutSeconds, TimeUnit.SECONDS);
            postThreadReply(messageTs, outcome, request);
            return outcome;
        } catch (TimeoutException e) {
            pending.remove(request.incidentId());
            HumanApprovalOutcome outcome = HumanApprovalOutcome.timeout(timeoutSeconds);
            postThreadReply(messageTs, outcome, request);
            return outcome;
        } catch (Exception e) {
            pending.remove(request.incidentId());
            throw new RuntimeException("Slack approval future failed: " + e.getMessage(), e);
        }
    }

    private void completePending(
            String incidentId,
            boolean approve,
            com.slack.api.bolt.context.builtin.ActionContext ctx,
            com.slack.api.bolt.request.builtin.BlockActionRequest req) {
        Pending p = pending.remove(incidentId);
        if (p == null) {
            log.warn(
                    "Slack action received for unknown or already-resolved incident {}",
                    incidentId);
            return;
        }
        String approver =
                req.getPayload().getUser() == null
                        ? "unknown"
                        : req.getPayload().getUser().getName();
        if (approve) {
            ApprovalToken token =
                    tokenIssuer.mint(
                            "slack:" + approver, p.request.audience(), p.request.scope(), 60);
            String wire = tokenIssuer.issue(token);
            log.info("Slack approval GRANTED for {} by {}", incidentId, approver);
            p.future.complete(HumanApprovalOutcome.approved(wire, approver));
        } else {
            log.info("Slack approval DENIED for {} by {}", incidentId, approver);
            p.future.complete(HumanApprovalOutcome.denied(approver, "human denied via Slack"));
        }
    }

    private void postThreadReply(
            String parentTs, HumanApprovalOutcome outcome, HumanApprovalRequest request) {
        try {
            String body =
                    outcome.approved()
                            ? ":white_check_mark: approved by `"
                                    + outcome.approver()
                                    + "` — token minted, agent proceeding with `"
                                    + request.proposedTool()
                                    + "`"
                            : ":x: "
                                    + outcome.reason()
                                    + " (`"
                                    + outcome.approver()
                                    + "`) — refusal verdict will be emitted, no write tool"
                                    + " invoked";
            slackClient.chatPostMessage(b -> b.channel(channel).threadTs(parentTs).text(body));
        } catch (Exception e) {
            log.warn("failed to post Slack thread reply: {}", e.getMessage());
        }
    }

    private record Pending(
            HumanApprovalRequest request, CompletableFuture<HumanApprovalOutcome> future) {}
}

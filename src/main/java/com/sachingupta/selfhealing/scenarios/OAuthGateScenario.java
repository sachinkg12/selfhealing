package com.sachingupta.selfhealing.scenarios;

import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.mcp.servers.DeployMcpServer;
import com.sachingupta.selfhealing.security.acl.PrincipalAclResolver;
import com.sachingupta.selfhealing.security.acl.ToolAcl;
import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import com.sachingupta.selfhealing.security.oauth.ApprovalToken;
import com.sachingupta.selfhealing.security.oauth.TokenException;
import com.sachingupta.selfhealing.security.oauth.TokenIssuer;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scenario 5: a remediator with the right role and ACL still cannot run a write tool without a
 * fresh approval token whose audience matches the target MCP server. We try three ways: no token,
 * wrong-audience token, expired token. All three are rejected by the Deploy MCP. Exercises the
 * human-in-the-loop gate and the OAuth audience / expiry / scope claim checks.
 */
@Component
public class OAuthGateScenario implements Scenario {

    private static final Logger log = LoggerFactory.getLogger(OAuthGateScenario.class);

    private final DeployMcpServer deployServer;
    private final PrincipalAclResolver aclResolver;
    private final TokenIssuer tokenIssuer;

    public OAuthGateScenario(
            DeployMcpServer deployServer,
            PrincipalAclResolver aclResolver,
            TokenIssuer tokenIssuer) {
        this.deployServer = deployServer;
        this.aclResolver = aclResolver;
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    public String slug() {
        return "oauth-gate";
    }

    @Override
    public String title() {
        return "Write tool blocked when approval token is missing / wrong / expired";
    }

    @Override
    public String claim() {
        return "Human-in-the-loop gate + OAuth audience scoping";
    }

    @Override
    public void run() {
        AgentIdentity remediator = Identities.searchRemediator();
        aclResolver.install(
                remediator, new ToolAcl().permit("rollback", "search").permit("history", "search"));

        attempt("no token", null, remediator);
        attempt(
                "wrong audience",
                tokenIssuer.issue(
                        tokenIssuer.mint("oncall@example.com", "notification-mcp", "search", 60)),
                remediator);
        // Build a properly-formed but already-stale token (iat 120s ago, exp 60s
        // ago). HMAC signs it blindly; the real JWT issuer requires exp > iat
        // before signing, so we cannot use a negative TTL here.
        ApprovalToken stale =
                new ApprovalToken(
                        "oncall@example.com",
                        DeployMcpServer.NAME,
                        "search",
                        Instant.now().minusSeconds(120),
                        Instant.now().minusSeconds(60));
        attempt("expired", tokenIssuer.issue(stale), remediator);
        attempt(
                "correctly issued",
                tokenIssuer.issue(
                        tokenIssuer.mint("oncall@example.com", DeployMcpServer.NAME, "search", 60)),
                remediator);
    }

    private void attempt(String label, String token, AgentIdentity identity) {
        try {
            deployServer.invoke(
                    McpToolInvocation.write(
                            identity,
                            "rollback",
                            "search",
                            Map.of("service", "search-api", "target_revision", "rev-prev"),
                            token));
            log.info("[{}] accepted", label);
        } catch (TokenException e) {
            log.info("[{}] rejected: {}", label, e.getMessage());
        }
    }
}

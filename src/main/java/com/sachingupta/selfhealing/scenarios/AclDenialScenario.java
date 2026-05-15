package com.sachingupta.selfhealing.scenarios;

import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.mcp.servers.DeployMcpServer;
import com.sachingupta.selfhealing.security.acl.PrincipalAclResolver;
import com.sachingupta.selfhealing.security.acl.ToolAcl;
import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import com.sachingupta.selfhealing.security.identity.Role;
import com.sachingupta.selfhealing.security.oauth.TokenIssuer;
import com.sachingupta.selfhealing.security.rbac.RbacDeniedException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scenario 4: a remediator authorized for the {@code search} scope attempts to roll back a service
 * in the {@code auth} scope. RBAC rejects because the principal does not hold the target scope.
 */
@Component
public class AclDenialScenario implements Scenario {

    private static final Logger log = LoggerFactory.getLogger(AclDenialScenario.class);

    private final DeployMcpServer deployServer;
    private final PrincipalAclResolver aclResolver;
    private final TokenIssuer tokenIssuer;

    public AclDenialScenario(
            DeployMcpServer deployServer,
            PrincipalAclResolver aclResolver,
            TokenIssuer tokenIssuer) {
        this.deployServer = deployServer;
        this.aclResolver = aclResolver;
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    public String slug() {
        return "acl-denial";
    }

    @Override
    public String title() {
        return "Remediator (search) attempts rollback in auth scope → denied";
    }

    @Override
    public String claim() {
        return "Per-tool ACL: search-scope principal cannot reach auth scope";
    }

    @Override
    public void run() {
        AgentIdentity searchOnly =
                new AgentIdentity("search-remediator-only", Role.REMEDIATOR, Set.of("search"));
        aclResolver.install(
                searchOnly,
                new ToolAcl()
                        .permit("rollback", "search") // only search
                        .permit("history", "search"));

        String token =
                tokenIssuer.issue(
                        tokenIssuer.mint("oncall@example.com", DeployMcpServer.NAME, "auth", 60));

        try {
            deployServer.invoke(
                    McpToolInvocation.write(
                            searchOnly,
                            "rollback",
                            "auth", // wrong scope for this principal
                            Map.of("service", "auth-api", "target_revision", "rev-prev"),
                            token));
            log.error("expected denial; none thrown");
        } catch (RbacDeniedException denied) {
            log.info("denied (expected): {}", denied.getMessage());
        }
    }
}

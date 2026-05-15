package com.sachingupta.selfhealing.scenarios;

import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.mcp.servers.DeployMcpServer;
import com.sachingupta.selfhealing.security.acl.PrincipalAclResolver;
import com.sachingupta.selfhealing.security.acl.ToolAcl;
import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import com.sachingupta.selfhealing.security.rbac.RbacDeniedException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scenario 3: a triage-role agent attempts a write tool. The RBAC guard rejects before any backend
 * is touched. A triage role can read from any tool server but cannot call any write tool.
 */
@Component
public class RbacDenialScenario implements Scenario {

    private static final Logger log = LoggerFactory.getLogger(RbacDenialScenario.class);

    private final DeployMcpServer deployServer;
    private final PrincipalAclResolver aclResolver;

    public RbacDenialScenario(DeployMcpServer deployServer, PrincipalAclResolver aclResolver) {
        this.deployServer = deployServer;
        this.aclResolver = aclResolver;
    }

    @Override
    public String slug() {
        return "rbac-denial";
    }

    @Override
    public String title() {
        return "Triage role attempts a write tool → RbacDeniedException";
    }

    @Override
    public String claim() {
        return "RBAC: triage cannot call write tools";
    }

    @Override
    public void run() {
        AgentIdentity triage = Identities.triage();
        aclResolver.install(
                triage,
                new ToolAcl()
                        .permit("history", "search")
                        .permit("current_revision", "search")
                        .permit("rollback", "search")); // ACL would allow, RBAC will not.

        try {
            deployServer.invoke(
                    McpToolInvocation.write(
                            triage,
                            "rollback",
                            "search",
                            Map.of("service", "search-api", "target_revision", "rev-prev"),
                            "irrelevant-token"));
            log.error("expected RbacDeniedException, none thrown");
        } catch (RbacDeniedException denied) {
            log.info("RBAC denied (expected): {}", denied.getMessage());
        }
    }
}

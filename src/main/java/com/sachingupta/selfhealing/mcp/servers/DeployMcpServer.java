package com.sachingupta.selfhealing.mcp.servers;

import com.sachingupta.selfhealing.mcp.api.McpTool;
import com.sachingupta.selfhealing.mcp.infrastructure.AbstractMcpServer;
import com.sachingupta.selfhealing.security.acl.AclGuard;
import com.sachingupta.selfhealing.security.acl.PrincipalAclResolver;
import com.sachingupta.selfhealing.security.oauth.TokenVerifier;
import com.sachingupta.selfhealing.security.rbac.RbacGuard;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Adapter wrapping a Kubernetes/CI-style deployment system as MCP tools. Exposes read tools
 * (history, current_revision) and write tools (rollback, scale, restart); writes require an
 * audience-scoped OAuth approval token at invoke time.
 */
@Component
public class DeployMcpServer extends AbstractMcpServer {

    public static final String NAME = "deploy-mcp";

    private final DeployBackend backend;

    public DeployMcpServer(
            RbacGuard rbacGuard,
            AclGuard aclGuard,
            TokenVerifier tokenVerifier,
            PrincipalAclResolver aclResolver,
            DeployBackend backend) {
        super(rbacGuard, aclGuard, tokenVerifier, aclResolver);
        this.backend = backend;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<McpTool> tools() {
        return List.of(
                McpTool.read("history", "recent rollouts for the service"),
                McpTool.read("current_revision", "currently deployed revision"),
                McpTool.write("rollback", "roll back to a prior revision"),
                McpTool.write("scale", "scale the deployment replica count"),
                McpTool.write("restart", "rolling restart of the deployment"));
    }

    @Override
    protected Object execute(McpTool tool, String scope, Map<String, Object> arguments) {
        return switch (tool.name()) {
            case "history" -> backend.history(scope);
            case "current_revision" -> backend.currentRevision(scope);
            case "rollback" -> backend.rollback(scope, arguments);
            case "scale" -> backend.scale(scope, arguments);
            case "restart" -> backend.restart(scope);
            default -> throw new IllegalArgumentException("unhandled tool: " + tool.name());
        };
    }
}

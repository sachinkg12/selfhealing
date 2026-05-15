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
 * Adapter wrapping a Slack/paging backend as MCP tools. The {@code post} tool is treated as a
 * non-destructive write: it changes external state but does not put production at risk, so the
 * agent calls it without the OAuth gate. Real deployments may choose differently.
 */
@Component
public class NotificationMcpServer extends AbstractMcpServer {

    public static final String NAME = "notification-mcp";

    private final NotificationFakeBackend backend;

    public NotificationMcpServer(
            RbacGuard rbacGuard,
            AclGuard aclGuard,
            TokenVerifier tokenVerifier,
            PrincipalAclResolver aclResolver,
            NotificationFakeBackend backend) {
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
                McpTool.read("post", "post a structured summary to Slack/pager"),
                McpTool.read(
                        "thread_reply", "append an evidence-ledger reply to a posted message"));
    }

    @Override
    protected Object execute(McpTool tool, String scope, Map<String, Object> arguments) {
        return switch (tool.name()) {
            case "post" -> backend.post(scope, arguments);
            case "thread_reply" -> backend.threadReply(scope, arguments);
            default -> throw new IllegalArgumentException("unhandled tool: " + tool.name());
        };
    }
}

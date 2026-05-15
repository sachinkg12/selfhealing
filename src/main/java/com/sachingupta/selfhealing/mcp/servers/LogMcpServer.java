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
 * Adapter wrapping a log-store backend (Loki/ELK-style) as MCP tools. The Log MCP timeout flag lets
 * the refusal scenario simulate incomplete telemetry.
 */
@Component
public class LogMcpServer extends AbstractMcpServer {

    public static final String NAME = "log-mcp";

    private final LogBackend backend;

    public LogMcpServer(
            RbacGuard rbacGuard,
            AclGuard aclGuard,
            TokenVerifier tokenVerifier,
            PrincipalAclResolver aclResolver,
            LogBackend backend) {
        super(rbacGuard, aclGuard, tokenVerifier, aclResolver);
        this.backend = backend;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<McpTool> tools() {
        return List.of(McpTool.read("search", "structured log search by service, time, severity"));
    }

    @Override
    protected Object execute(McpTool tool, String scope, Map<String, Object> arguments) {
        if ("search".equals(tool.name())) {
            return backend.search(scope, arguments);
        }
        throw new IllegalArgumentException("unhandled tool: " + tool.name());
    }
}

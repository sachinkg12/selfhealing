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
 * Adapter (Adapter pattern) wrapping a Prometheus-style metrics backend as MCP tools. Three tools:
 * range_query, instant_query, alert_state.
 */
@Component
public class PrometheusMcpServer extends AbstractMcpServer {

    public static final String NAME = "prometheus-mcp";

    private final MetricsBackend backend;

    public PrometheusMcpServer(
            RbacGuard rbacGuard,
            AclGuard aclGuard,
            TokenVerifier tokenVerifier,
            PrincipalAclResolver aclResolver,
            MetricsBackend backend) {
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
                McpTool.read("range_query", "p99/p50 latency over a time window"),
                McpTool.read("instant_query", "metric value at a point in time"),
                McpTool.read("alert_state", "currently firing alerts"));
    }

    @Override
    protected Object execute(McpTool tool, String scope, Map<String, Object> arguments) {
        return switch (tool.name()) {
            case "range_query" -> backend.rangeQuery(scope, arguments);
            case "instant_query" -> backend.instantQuery(scope, arguments);
            case "alert_state" -> backend.alertState(scope);
            default -> throw new IllegalArgumentException("unhandled tool: " + tool.name());
        };
    }
}

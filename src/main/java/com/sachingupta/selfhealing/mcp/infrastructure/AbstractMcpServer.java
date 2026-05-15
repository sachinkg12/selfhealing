package com.sachingupta.selfhealing.mcp.infrastructure;

import com.sachingupta.selfhealing.mcp.api.McpServer;
import com.sachingupta.selfhealing.mcp.api.McpTool;
import com.sachingupta.selfhealing.mcp.api.McpToolInvocation;
import com.sachingupta.selfhealing.security.acl.AclGuard;
import com.sachingupta.selfhealing.security.acl.PrincipalAclResolver;
import com.sachingupta.selfhealing.security.oauth.TokenVerifier;
import com.sachingupta.selfhealing.security.rbac.RbacGuard;
import com.sachingupta.selfhealing.verdict.McpResponse;
import java.util.Map;

/**
 * Template Method base for MCP servers.
 *
 * <p>Fixed sequence applied to every call (security envelope):
 *
 * <ol>
 *   <li>locate the tool in the local catalogue,
 *   <li>RBAC check (read vs write),
 *   <li>ACL check (tool+scope must be permitted for the principal),
 *   <li>OAuth approval-token check for write tools,
 *   <li>delegate to {@link #execute} for the server-specific logic.
 * </ol>
 *
 * <p>Subclasses contribute only the tool catalogue and the execute step. The security envelope is
 * identical for every server, so it is captured once here (DRY, Template Method).
 */
public abstract class AbstractMcpServer implements McpServer {

    protected final RbacGuard rbacGuard;
    protected final AclGuard aclGuard;
    protected final TokenVerifier tokenVerifier;
    protected final PrincipalAclResolver aclResolver;

    protected AbstractMcpServer(
            RbacGuard rbacGuard,
            AclGuard aclGuard,
            TokenVerifier tokenVerifier,
            PrincipalAclResolver aclResolver) {
        this.rbacGuard = rbacGuard;
        this.aclGuard = aclGuard;
        this.tokenVerifier = tokenVerifier;
        this.aclResolver = aclResolver;
    }

    @Override
    public final McpResponse invoke(McpToolInvocation invocation) {
        long started = System.currentTimeMillis();
        McpTool tool = lookup(invocation.toolName());

        if (tool.write()) {
            rbacGuard.authorizeWrite(invocation.identity(), invocation.scope());
            aclGuard.enforce(
                    aclResolver.resolve(invocation.identity()), tool.name(), invocation.scope());
            tokenVerifier.verify(invocation.approvalToken(), name(), invocation.scope());
        } else {
            rbacGuard.authorizeRead(invocation.identity());
            aclGuard.enforce(
                    aclResolver.resolve(invocation.identity()), tool.name(), invocation.scope());
        }

        Object payload = execute(tool, invocation.scope(), invocation.arguments());
        long elapsed = System.currentTimeMillis() - started;
        String signal = extractSignal(payload);
        return McpResponse.success(
                shortName(), tool.name(), invocation.arguments(), payload, signal, elapsed);
    }

    /** Subclasses produce the server-specific result. */
    protected abstract Object execute(McpTool tool, String scope, Map<String, Object> arguments);

    /**
     * Short server name used as the prefix for the qualified tool identifier on the wire. Strips
     * the standard {@code -mcp} suffix from the bean's {@link McpServer#name()} so the verdict
     * carries names like {@code prometheus.range_query} / {@code deploy.history}.
     */
    protected String shortName() {
        String n = name();
        return n.endsWith("-mcp") ? n.substring(0, n.length() - 4) : n;
    }

    private static String extractSignal(Object payload) {
        if (payload instanceof Map<?, ?> m && m.containsKey("signal")) {
            return String.valueOf(m.get("signal"));
        }
        return "ok";
    }

    private McpTool lookup(String name) {
        return tools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "unknown tool '" + name + "' on server " + name()));
    }
}

package com.sachingupta.selfhealing.mcp.infrastructure;

import com.sachingupta.selfhealing.mcp.api.McpRegistry;
import com.sachingupta.selfhealing.mcp.api.McpServer;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registry implementation backed by Spring's component scan: every {@link McpServer} bean is
 * automatically discoverable. Active by default; replaced by {@code RemoteMcpRegistry} when {@code
 * selfhealing.mcp.mode=http}.
 */
@Component
@ConditionalOnProperty(
        name = "selfhealing.mcp.mode",
        havingValue = "in-process",
        matchIfMissing = true)
public class McpRegistryImpl implements McpRegistry {

    private final List<McpServer> servers;

    public McpRegistryImpl(List<McpServer> servers) {
        this.servers = List.copyOf(servers);
    }

    @Override
    public List<McpServer> servers() {
        return servers;
    }

    @Override
    public Optional<McpServer> findByName(String name) {
        return servers.stream().filter(s -> s.name().equals(name)).findFirst();
    }
}

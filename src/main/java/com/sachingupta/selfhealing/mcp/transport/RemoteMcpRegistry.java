package com.sachingupta.selfhealing.mcp.transport;

import com.sachingupta.selfhealing.mcp.api.McpRegistry;
import com.sachingupta.selfhealing.mcp.api.McpServer;
import com.sachingupta.selfhealing.mcp.api.McpTool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MCP-wire-protocol implementation of {@link McpRegistry}. Active only when {@code
 * selfhealing.mcp.mode=http}.
 *
 * <p>The MCP client (transport + SSE handshake) is constructed lazily on first {@code
 * findByName}/{@code servers} call. The embedded Tomcat that hosts the MCP server endpoint starts
 * during {@code finishRefresh()} — singletons are instantiated earlier in that lifecycle, so any
 * eager attempt to open an SSE stream during bean creation hits a port that isn't listening yet.
 * Deferring all client work until the agent's {@code CommandLineRunner} phase sidesteps that race.
 *
 * <p>{@code tools/list} response is regrouped by qualified-name prefix (e.g. {@code
 * prometheus.range_query} → server {@code prometheus-mcp}) so the agent's existing {@link
 * McpRegistry#findByName} lookup continues to work unchanged (Open/Closed, Liskov).
 */
@Component
@ConditionalOnProperty(name = "selfhealing.mcp.mode", havingValue = "http")
public class RemoteMcpRegistry implements McpRegistry {

    private static final Logger log = LoggerFactory.getLogger(RemoteMcpRegistry.class);

    private final int port;
    private volatile McpSyncClient client;
    private volatile List<McpServer> servers;

    public RemoteMcpRegistry(@Value("${selfhealing.mcp.http-port:8765}") int port) {
        this.port = port;
    }

    private List<McpServer> ensureLoaded() {
        List<McpServer> snapshot = servers;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (servers == null) {
                client = buildClient(port);
                client.initialize();
                servers = discover(client);
                log.info(
                        "Remote MCP registry initialized with {} logical servers, {} tools total",
                        servers.size(),
                        servers.stream().mapToInt(s -> s.tools().size()).sum());
            }
            return servers;
        }
    }

    private static McpSyncClient buildClient(int port) {
        String baseUri = "http://localhost:" + port;
        HttpClientSseClientTransport transport =
                HttpClientSseClientTransport.builder(baseUri).sseEndpoint("/sse").build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }

    private static List<McpServer> discover(McpSyncClient client) {
        McpSchema.ListToolsResult listed = client.listTools();
        Map<String, List<McpTool>> byServer = new LinkedHashMap<>();
        for (McpSchema.Tool tool : listed.tools()) {
            String qualified = tool.name();
            int dot = qualified.indexOf('.');
            if (dot < 0) {
                continue;
            }
            String shortName = qualified.substring(0, dot);
            String bareName = qualified.substring(dot + 1);
            boolean write = isWriteToolName(bareName);
            McpTool localTool =
                    write
                            ? McpTool.write(bareName, tool.description())
                            : McpTool.read(bareName, tool.description());
            byServer.computeIfAbsent(shortName, k -> new ArrayList<>()).add(localTool);
        }
        List<McpServer> built = new ArrayList<>();
        for (Map.Entry<String, List<McpTool>> e : byServer.entrySet()) {
            built.add(new RemoteMcpServer(e.getKey() + "-mcp", e.getValue(), client));
        }
        return Collections.unmodifiableList(built);
    }

    private static boolean isWriteToolName(String name) {
        return switch (name) {
            case "rollback", "scale", "restart", "post", "thread_reply" -> true;
            default -> false;
        };
    }

    @Override
    public List<McpServer> servers() {
        return ensureLoaded();
    }

    @Override
    public Optional<McpServer> findByName(String name) {
        return ensureLoaded().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    @PreDestroy
    void close() {
        McpSyncClient c = client;
        if (c != null) {
            try {
                c.closeGracefully();
            } catch (RuntimeException e) {
                log.warn("MCP client close failed: {}", e.getMessage());
            }
        }
    }
}

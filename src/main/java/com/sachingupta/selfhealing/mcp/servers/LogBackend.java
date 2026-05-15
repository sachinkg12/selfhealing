package com.sachingupta.selfhealing.mcp.servers;

import java.util.Map;

/**
 * Stable contract used by {@link LogMcpServer}. Implementations adapt Loki/ELK or an in-memory
 * fake.
 */
public interface LogBackend {

    Map<String, Object> search(String scope, Map<String, Object> arguments);
}

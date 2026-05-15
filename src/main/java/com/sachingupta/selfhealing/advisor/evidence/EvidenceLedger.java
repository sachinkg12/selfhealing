package com.sachingupta.selfhealing.advisor.evidence;

import com.sachingupta.selfhealing.verdict.McpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository (Repository pattern) of all tool calls and responses for a single incident. Every tool
 * call, argument, and response is recorded here and later attached to the final verdict for audit.
 */
public final class EvidenceLedger {

    private final List<McpResponse> entries = new ArrayList<>();

    public void record(McpResponse response) {
        entries.add(response);
    }

    public List<McpResponse> entries() {
        return List.copyOf(entries);
    }

    public Optional<McpResponse> latestFor(String tool) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).tool().equals(tool)) {
                return Optional.of(entries.get(i));
            }
        }
        return Optional.empty();
    }

    public int size() {
        return entries.size();
    }

    public int successCount() {
        return (int) entries.stream().filter(McpResponse::ok).count();
    }
}

package com.sachingupta.selfhealing.verdict;

/**
 * Reference to a single piece of evidence retrieved during an incident. Embedded in {@link
 * Verdict#evidence()}.
 */
public record EvidenceRef(String tool, String signal) {}

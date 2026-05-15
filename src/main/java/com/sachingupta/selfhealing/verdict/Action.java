package com.sachingupta.selfhealing.verdict;

import java.util.Map;

/**
 * The action block of a remediate verdict. Carries the approval token required by write tools at
 * the Deploy MCP server boundary.
 */
public record Action(String tool, Map<String, Object> arguments, String approvalToken) {}

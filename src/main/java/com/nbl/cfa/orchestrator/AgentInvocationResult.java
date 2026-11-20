package com.nbl.cfa.orchestrator;

import tools.jackson.databind.JsonNode;

public record AgentInvocationResult(
        String agentId,
        boolean timedOut,
        int exitCode,
        JsonNode payload,
        String rawOutput) {
}

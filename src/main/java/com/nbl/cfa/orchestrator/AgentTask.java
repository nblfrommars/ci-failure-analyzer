package com.nbl.cfa.orchestrator;

import java.util.Map;

public record AgentTask(String taskId, String task, Map<String, Object> input) {
}

package com.nbl.cfa.orchestrator;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Điều phối task qua OpenClaw và ghi lại mọi transition vào audit store. */
@Service
public class OrchestratorCoordinator {

    private final OpenClawAgentClient agentClient;
    private final OrchestratorStateMachine stateMachine;
    private final OrchestratorAuditStore auditStore;
    private final JsonMapper jsonMapper;

    public OrchestratorCoordinator(OpenClawAgentClient agentClient,
                                   OrchestratorStateMachine stateMachine,
                                   OrchestratorAuditStore auditStore,
                                   JsonMapper jsonMapper) {
        this.agentClient = agentClient;
        this.stateMachine = stateMachine;
        this.auditStore = auditStore;
        this.jsonMapper = jsonMapper;
    }

    public OrchestratorContext start(AgentTask task, String repoFullName,
                                     Long githubRunId, Integer runAttempt,
                                     String llmProfile, String llmModel) {
        Objects.requireNonNull(task, "task không được null");
        if (repoFullName == null || repoFullName.isBlank()) {
            throw new IllegalArgumentException("repoFullName không được rỗng");
        }
        OrchestratorRun run = stateMachine.initial(task.taskId());
        long auditRunId = auditStore.createRun(task.taskId(), repoFullName,
                githubRunId, runAttempt, run.state(), llmProfile, llmModel);
        return new OrchestratorContext(auditRunId, run);
    }

    public OrchestratorContext transition(OrchestratorContext context,
                                          OrchestratorEvent event,
                                          String agentId, String status,
                                          JsonNode payload) {
        Objects.requireNonNull(context, "context không được null");
        OrchestratorRun before = context.run();
        OrchestratorRun after = stateMachine.transition(before, event);
        auditStore.updateState(context.auditRunId(), after.state());
        auditStore.recordEvent(context.auditRunId(), before.state(), event,
                after.state(), agentId, status, toJson(payload));
        return new OrchestratorContext(context.auditRunId(), after);
    }

    public AgentInvocationResult executeAgent(OrchestratorContext context,
                                              AgentTask task,
                                              OrchestratorEvent successEvent,
                                              OrchestratorEvent failureEvent) {
        String agentId = resolveAgent(task.task());
        AgentInvocationResult result = agentClient.call(agentId, task);
        boolean success = !result.timedOut()
                && result.exitCode() == 0
                && result.payload() != null
                && !"error".equalsIgnoreCase(result.payload().path("status").asText());
        transition(context, success ? successEvent : failureEvent, agentId,
                success ? "ok" : "failed", result.payload());
        return result;
    }

    public String agentFor(String task) {
        return resolveAgent(task);
    }

    private String resolveAgent(String task) {
        return switch (task) {
            case "deploy" -> "deploy";
            case "test" -> "test";
            case "triage" -> "triage";
            default -> throw new IllegalArgumentException("Task không hợp lệ: " + task);
        };
    }

    private String toJson(JsonNode payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"serialization_error\":true}";
        }
    }
}

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
        return executeAgentStep(context, task, successEvent, failureEvent).result();
    }

    public AgentStepResult executeAgentWithContext(OrchestratorContext context,
                                                   AgentTask task,
                                                   OrchestratorEvent successEvent,
                                                   OrchestratorEvent failureEvent) {
        AgentStep step = executeAgentStep(context, task, successEvent, failureEvent);
        return new AgentStepResult(step.context(), step.result());
    }

    public AgentInvocationResult runAgent(AgentTask task) {
        return agentClient.call(resolveAgent(task.task()), task);
    }

    /** Route Triage sang rollback/heal; agent không tự giao tiếp với nhau. */
    public BranchExecution routeTriage(OrchestratorContext context, JsonNode triagePayload,
                                       AgentTask rollbackTask, AgentTask healTask) {
        Objects.requireNonNull(context, "context không được null");
        Objects.requireNonNull(triagePayload, "triagePayload không được null");
        String verdict = triagePayload.path("verdict").asText("");
        return switch (verdict) {
            case "APP_BUG" -> {
                OrchestratorContext next = transition(context, OrchestratorEvent.TRIAGE_APP_BUG,
                        "triage", "ok", triagePayload);
                AgentStep step = executeAgentStep(next, rollbackTask,
                        OrchestratorEvent.ROLLBACK_SUCCEEDED, OrchestratorEvent.ROLLBACK_FAILED);
                yield new BranchExecution("APP_BUG", step.context(), step.result());
            }
            case "STALE_TEST" -> {
                AgentTask boundedHealTask = boundHealRounds(healTask);
                OrchestratorContext next = transition(context, OrchestratorEvent.TRIAGE_STALE_TEST,
                        "triage", "ok", triagePayload);
                AgentStep step = executeAgentStep(next, boundedHealTask,
                        OrchestratorEvent.HEAL_SUCCEEDED, OrchestratorEvent.HEAL_FAILED);
                yield new BranchExecution("STALE_TEST", step.context(), step.result());
            }
            case "needs_human" -> {
                OrchestratorContext next = transition(context, OrchestratorEvent.TRIAGE_NEEDS_HUMAN,
                        "triage", "ok", triagePayload);
                yield new BranchExecution("needs_human", next, null);
            }
            default -> throw new IllegalArgumentException("Verdict Triage không hợp lệ: " + verdict);
        };
    }

    private AgentStep executeAgentStep(OrchestratorContext context,
                                       AgentTask task,
                                       OrchestratorEvent successEvent,
                                       OrchestratorEvent failureEvent) {
        String agentId = resolveAgent(task.task());
        AgentInvocationResult result = agentClient.call(agentId, task);
        String status = result.payload() == null ? "" : result.payload().path("status").asText("");
        boolean success = !result.timedOut()
                && result.exitCode() == 0
                && result.payload() != null
                && ("ok".equalsIgnoreCase(status)
                || "passed".equalsIgnoreCase(status)
                || "completed".equalsIgnoreCase(status));
        OrchestratorContext next = transition(context, success ? successEvent : failureEvent, agentId,
                success ? "ok" : "failed", result.payload());
        return new AgentStep(next, result);
    }

    private AgentTask boundHealRounds(AgentTask task) {
        Objects.requireNonNull(task, "healTask không được null");
        Map<String, Object> input = new java.util.HashMap<>(task.input() == null ? Map.of() : task.input());
        int round = 1;
        Object rawRound = input.get("heal_round");
        if (rawRound instanceof Number number) {
            round = number.intValue();
        }
        if (round < 1 || round > 3) {
            throw new IllegalStateException("Heal vượt quá giới hạn 3 vòng");
        }
        input.put("max_heal_rounds", 3);
        input.put("heal_round", round);
        return new AgentTask(task.taskId(), task.task(), Map.copyOf(input));
    }

    public record BranchExecution(String verdict, OrchestratorContext context,
                                  AgentInvocationResult actionResult) {
    }

    public record AgentStepResult(OrchestratorContext context, AgentInvocationResult result) {
    }

    private record AgentStep(OrchestratorContext context, AgentInvocationResult result) {
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

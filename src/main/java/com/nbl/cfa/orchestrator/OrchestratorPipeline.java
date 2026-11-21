package com.nbl.cfa.orchestrator;

import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Chuỗi điều phối có thể chạy với agent thật hoặc agent mock trong test. */
@Service
public class OrchestratorPipeline {

    private final OrchestratorCoordinator coordinator;

    public OrchestratorPipeline(OrchestratorCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public OrchestratorContext run(OrchestratorContext context,
                                   AgentTask deployTask,
                                   AgentTask testTask,
                                   AgentTask triageTask,
                                   AgentTask rollbackTask,
                                   AgentTask healTask) {
        Objects.requireNonNull(context, "context không được null");
        context = coordinator.transition(context, OrchestratorEvent.START_DEPLOY,
                null, "accepted", null);
        OrchestratorCoordinator.AgentStepResult deployStep = coordinator.executeAgentWithContext(
                context, deployTask, OrchestratorEvent.DEPLOY_SUCCEEDED, OrchestratorEvent.DEPLOY_FAILED);
        context = deployStep.context();
        AgentInvocationResult deploy = deployStep.result();
        if (!successful(deploy)) {
            return context;
        }

        OrchestratorCoordinator.AgentStepResult testStep = coordinator.executeAgentWithContext(
                context, testTask, OrchestratorEvent.TEST_PASSED, OrchestratorEvent.TEST_FAILED);
        context = testStep.context();
        AgentInvocationResult test = testStep.result();
        if (successful(test)) {
            return context;
        }

        AgentInvocationResult triage = coordinator.runAgent(triageTask);
        JsonNode payload = triage.payload();
        if (!successful(triage) || payload == null) {
            return new OrchestratorContext(context.auditRunId(),
                    new OrchestratorRun(context.run().taskId(), OrchestratorState.NEEDS_HUMAN));
        }
        return coordinator.routeTriage(context, payload, rollbackTask, healTask).context();
    }

    private boolean successful(AgentInvocationResult result) {
        String status = result == null || result.payload() == null
                ? "" : result.payload().path("status").asText("");
        return result != null && !result.timedOut() && result.exitCode() == 0
                && result.payload() != null
                && ("ok".equalsIgnoreCase(status)
                || "passed".equalsIgnoreCase(status)
                || "completed".equalsIgnoreCase(status));
    }
}

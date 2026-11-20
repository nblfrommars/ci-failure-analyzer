package com.nbl.cfa.orchestrator;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * State machine thuần điều phối: chỉ quyết định transition, không gọi LLM/MCP và không có side effect.
 */
@Component
public class OrchestratorStateMachine {

    private final Map<OrchestratorState, Map<OrchestratorEvent, OrchestratorState>> transitions;

    public OrchestratorStateMachine() {
        EnumMap<OrchestratorState, Map<OrchestratorEvent, OrchestratorState>> table =
                new EnumMap<>(OrchestratorState.class);
        table.put(OrchestratorState.RECEIVED, Map.of(
                OrchestratorEvent.START_DEPLOY, OrchestratorState.DEPLOYING));
        table.put(OrchestratorState.DEPLOYING, Map.of(
                OrchestratorEvent.DEPLOY_SUCCEEDED, OrchestratorState.TESTING,
                OrchestratorEvent.DEPLOY_FAILED, OrchestratorState.FAILED));
        table.put(OrchestratorState.TESTING, Map.of(
                OrchestratorEvent.TEST_PASSED, OrchestratorState.PROMOTE_PENDING,
                OrchestratorEvent.TEST_FAILED, OrchestratorState.TRIAGING));
        table.put(OrchestratorState.TRIAGING, Map.of(
                OrchestratorEvent.TRIAGE_APP_BUG, OrchestratorState.ROLLING_BACK,
                OrchestratorEvent.TRIAGE_STALE_TEST, OrchestratorState.HEALING,
                OrchestratorEvent.TRIAGE_NEEDS_HUMAN, OrchestratorState.NEEDS_HUMAN));
        table.put(OrchestratorState.ROLLING_BACK, Map.of(
                OrchestratorEvent.ROLLBACK_SUCCEEDED, OrchestratorState.NEEDS_HUMAN,
                OrchestratorEvent.ROLLBACK_FAILED, OrchestratorState.FAILED));
        table.put(OrchestratorState.HEALING, Map.of(
                OrchestratorEvent.HEAL_SUCCEEDED, OrchestratorState.TESTING,
                OrchestratorEvent.HEAL_FAILED, OrchestratorState.NEEDS_HUMAN));
        table.put(OrchestratorState.PROMOTE_PENDING, Map.of(
                OrchestratorEvent.PROMOTION_APPROVED, OrchestratorState.COMPLETED,
                OrchestratorEvent.PROMOTION_REJECTED, OrchestratorState.NEEDS_HUMAN));
        this.transitions = Map.copyOf(table);
    }

    public OrchestratorRun initial(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId không được rỗng");
        }
        return new OrchestratorRun(taskId, OrchestratorState.RECEIVED);
    }

    public OrchestratorRun transition(OrchestratorRun run, OrchestratorEvent event) {
        if (run == null || event == null) {
            throw new IllegalArgumentException("run và event không được null");
        }
        OrchestratorState next = transitions
                .getOrDefault(run.state(), Map.of())
                .get(event);
        if (next == null) {
            throw new IllegalStateException(
                    "Transition không hợp lệ: " + run.state() + " + " + event);
        }
        return new OrchestratorRun(run.taskId(), next);
    }
}

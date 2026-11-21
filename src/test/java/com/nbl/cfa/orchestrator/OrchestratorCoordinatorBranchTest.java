package com.nbl.cfa.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.json.JsonMapper;

class OrchestratorCoordinatorBranchTest {

    @Mock
    private OpenClawAgentClient agentClient;

    @Mock
    private OrchestratorAuditStore auditStore;

    private OrchestratorCoordinator coordinator;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        coordinator = new OrchestratorCoordinator(agentClient, new OrchestratorStateMachine(),
                auditStore, jsonMapper);
    }

    @Test
    void appBugRoutesToDeployRollbackAndStopsForHuman() {
        when(agentClient.call(eq("deploy"), any(AgentTask.class))).thenReturn(success("rollback complete"));

        OrchestratorCoordinator.BranchExecution result = coordinator.routeTriage(
                triagingContext(), json("{\"verdict\":\"APP_BUG\",\"evidence\":[\"HTTP 500\"]}"),
                new AgentTask("run-rollback", "deploy", Map.of("action", "rollback", "env", "staging")),
                new AgentTask("unused", "test", Map.of()));

        assertEquals("APP_BUG", result.verdict());
        assertEquals(OrchestratorState.NEEDS_HUMAN, result.context().run().state());
        verify(agentClient).call("deploy", new AgentTask(
                "run-rollback", "deploy", Map.of("action", "rollback", "env", "staging")));
    }

    @Test
    void staleTestRoutesToTestHealAndReturnsToTesting() {
        when(agentClient.call(eq("test"), any(AgentTask.class))).thenReturn(success("healed"));

        OrchestratorCoordinator.BranchExecution result = coordinator.routeTriage(
                triagingContext(), json("{\"verdict\":\"STALE_TEST\",\"evidence\":[\"text changed\"]}"),
                new AgentTask("unused", "deploy", Map.of()),
                new AgentTask("run-heal", "test", Map.of("action", "heal", "heal_round", 2)));

        assertEquals("STALE_TEST", result.verdict());
        assertEquals(OrchestratorState.TESTING, result.context().run().state());
        verify(agentClient).call("test", new AgentTask("run-heal", "test",
                Map.of("action", "heal", "heal_round", 2, "max_heal_rounds", 3)));
    }

    @Test
    void healRoundFourIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                coordinator.routeTriage(triagingContext(),
                        json("{\"verdict\":\"STALE_TEST\"}"),
                        new AgentTask("unused", "deploy", Map.of()),
                        new AgentTask("run-heal", "test", Map.of("heal_round", 4))));
    }

    private OrchestratorContext triagingContext() {
        return new OrchestratorContext(1L, new OrchestratorRun("run-1", OrchestratorState.TRIAGING));
    }

    private tools.jackson.databind.JsonNode json(String value) {
        return jsonMapper.readTree(value);
    }

    private AgentInvocationResult success(String summary) {
        return new AgentInvocationResult("agent", false, 0,
                jsonMapper.readTree("{\"status\":\"ok\",\"summary\":\"" + summary + "\"}"), "{}");
    }
}

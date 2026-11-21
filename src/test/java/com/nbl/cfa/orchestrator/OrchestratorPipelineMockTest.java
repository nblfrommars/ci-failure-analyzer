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

class OrchestratorPipelineMockTest {

    @Mock
    private OpenClawAgentClient agentClient;

    @Mock
    private OrchestratorAuditStore auditStore;

    private OrchestratorPipeline pipeline;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(auditStore.createRun(any(), any(), any(), any(), any(), any(), any())).thenReturn(7L);
        OrchestratorCoordinator coordinator = new OrchestratorCoordinator(agentClient,
                new OrchestratorStateMachine(), auditStore, jsonMapper);
        pipeline = new OrchestratorPipeline(coordinator);
    }

    @Test
    void mockPassBranchEndsAtPromotionPending() {
        when(agentClient.call(eq("deploy"), any())).thenReturn(result("ok", "{}"));
        when(agentClient.call(eq("test"), any())).thenReturn(result("passed", "{}"));

        OrchestratorContext result = pipeline.run(initial(), task("deploy"), task("test"),
                task("triage"), new AgentTask("run-rollback", "deploy", Map.of("action", "rollback")),
                new AgentTask("run-heal", "test", Map.of("action", "heal")));

        assertEquals(OrchestratorState.PROMOTE_PENDING, result.run().state());
    }

    @Test
    void mockAppBugBranchRollsBackAndStopsForHuman() {
        when(agentClient.call(eq("deploy"), any())).thenReturn(result("ok", "{}"));
        when(agentClient.call(eq("test"), any())).thenReturn(result("failed", "{}"));
        when(agentClient.call(eq("triage"), any())).thenReturn(result("ok",
                "{\"verdict\":\"APP_BUG\",\"evidence\":[\"HTTP 500\"]}"));
        when(agentClient.call(eq("deploy"), any())).thenReturn(
                result("ok", "{}"), result("ok", "{}"));

        OrchestratorContext result = pipeline.run(initial(), task("deploy"), task("test"),
                task("triage"), new AgentTask("run-rollback", "deploy", Map.of("action", "rollback")),
                new AgentTask("run-heal", "test", Map.of("action", "heal")));

        assertEquals(OrchestratorState.NEEDS_HUMAN, result.run().state());
        verify(agentClient).call(eq("triage"), any(AgentTask.class));
    }

    @Test
    void mockStaleTestBranchHealsAndReturnsToTesting() {
        when(agentClient.call(eq("deploy"), any())).thenReturn(result("ok", "{}"));
        when(agentClient.call(eq("test"), any())).thenReturn(
                result("failed", "{}"), result("ok", "{}"));
        when(agentClient.call(eq("triage"), any())).thenReturn(result("ok",
                "{\"verdict\":\"STALE_TEST\",\"evidence\":[\"text changed\"]}"));

        OrchestratorContext result = pipeline.run(initial(), task("deploy"), task("test"),
                task("triage"), new AgentTask("run-rollback", "deploy", Map.of("action", "rollback")),
                new AgentTask("heal", "test", Map.of("action", "heal", "heal_round", 1)));

        assertEquals(OrchestratorState.TESTING, result.run().state());
    }

    private OrchestratorContext initial() {
        return new OrchestratorContext(7L, new OrchestratorRun("run-mock", OrchestratorState.RECEIVED));
    }

    private AgentTask task(String kind) {
        return new AgentTask("run-" + kind, kind, Map.of("mock", true));
    }

    private AgentInvocationResult result(String status, String payload) {
        String json = payload.equals("{}")
                ? "{\"status\":\"" + status + "\"}"
                : "{\"status\":\"" + status + "\"," + payload.substring(1);
        return new AgentInvocationResult("mock", false, 0,
                jsonMapper.readTree(json), "{}" );
    }
}

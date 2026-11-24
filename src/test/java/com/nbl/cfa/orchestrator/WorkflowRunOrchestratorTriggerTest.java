package com.nbl.cfa.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nbl.cfa.webhook.WorkflowRunMessage;
import com.nbl.cfa.repo.RepoRegistry;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.json.JsonMapper;

class WorkflowRunOrchestratorTriggerTest {

    private static final String REPO = "nblfrommars/ci-sandbox";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Mock
    private OrchestratorPipeline pipeline;

    @Mock
    private OrchestratorAuditStore auditStore;

    private OrchestratorCoordinator coordinator;

    private WorkflowRunOrchestratorTrigger trigger;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(auditStore.createRun(any(), any(), any(), any(), any(), any(), any())).thenReturn(10L);
        coordinator = new OrchestratorCoordinator(null, new OrchestratorStateMachine(),
                auditStore, JsonMapper.builder().build());
        trigger = new WorkflowRunOrchestratorTrigger(
                coordinator, pipeline, new RepoRegistry(java.nio.file.Path.of("config/repos.yml")),
                "cloud", "particle-ai/particle-1:free");
    }

    @Test
    void successfulDemoRunStartsDeployAndUsesCommitShaImage() {
        WorkflowRunMessage message = message(REPO, "success", SHA);
        OrchestratorContext context = new OrchestratorContext(
                10L, new OrchestratorRun("run-123-1", OrchestratorState.RECEIVED));
        trigger.handleSuccessfulRun(message);

        ArgumentCaptor<AgentTask> deployTask = ArgumentCaptor.forClass(AgentTask.class);
        verify(pipeline).run(eq(context), deployTask.capture(), any(AgentTask.class),
                any(AgentTask.class), any(AgentTask.class), any(AgentTask.class));
        assertEquals(new AgentTask("run-123-1", "deploy", java.util.Map.of(
                "tag", "ghcr.io/nblfrommars/ci-sandbox:sha-" + SHA,
                "env", "staging", "repo_full_name", REPO,
                "github_run_id", 123L, "run_attempt", 1)), deployTask.getValue());
    }

    @Test
    void ignoresNonDemoOrNonSuccessfulRuns() {
        trigger.handleSuccessfulRun(message("other/repo", "success", SHA));
        trigger.handleSuccessfulRun(message(REPO, "failure", SHA));
        trigger.handleSuccessfulRun(message(REPO, "success", "bad-sha"));

        verify(auditStore, never()).createRun(any(), any(), any(), any(), any(), any(), any());
    }

    private WorkflowRunMessage message(String repo, String conclusion, String sha) {
        return new WorkflowRunMessage(
                "delivery-1", 99L, repo, 123L, 1, sha, "main", 7,
                "completed", conclusion, "https://github.com/" + repo + "/actions/runs/123");
    }
}

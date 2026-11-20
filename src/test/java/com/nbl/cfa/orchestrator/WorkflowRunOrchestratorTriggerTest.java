package com.nbl.cfa.orchestrator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nbl.cfa.webhook.WorkflowRunMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class WorkflowRunOrchestratorTriggerTest {

    private static final String REPO = "nblfrommars/ci-sandbox";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Mock
    private OrchestratorCoordinator coordinator;

    private WorkflowRunOrchestratorTrigger trigger;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        trigger = new WorkflowRunOrchestratorTrigger(
                coordinator, REPO, "cloud", "particle-ai/particle-1:free");
    }

    @Test
    void successfulDemoRunStartsDeployAndUsesCommitShaImage() {
        WorkflowRunMessage message = message(REPO, "success", SHA);
        OrchestratorContext context = new OrchestratorContext(
                10L, new OrchestratorRun("run-123-1", OrchestratorState.RECEIVED));
        when(coordinator.start(any(), eq(REPO), eq(123L), eq(1), eq("cloud"),
                eq("particle-ai/particle-1:free"))).thenReturn(context);
        when(coordinator.transition(eq(context), eq(OrchestratorEvent.START_DEPLOY),
                eq(null), eq("accepted"), eq(null))).thenReturn(context);

        trigger.handleSuccessfulRun(message);

        verify(coordinator).start(
                eq(new AgentTask(
                        "run-123-1",
                        "deploy",
                        java.util.Map.of(
                                "tag", "ghcr.io/nblfrommars/ci-sandbox:sha-" + SHA,
                                "env", "staging",
                                "repo_full_name", REPO,
                                "github_run_id", 123L,
                                "run_attempt", 1))),
                eq(REPO), eq(123L), eq(1), eq("cloud"), eq("particle-ai/particle-1:free"));
        verify(coordinator).executeAgent(eq(context), any(AgentTask.class),
                eq(OrchestratorEvent.DEPLOY_SUCCEEDED), eq(OrchestratorEvent.DEPLOY_FAILED));
    }

    @Test
    void ignoresNonDemoOrNonSuccessfulRuns() {
        trigger.handleSuccessfulRun(message("other/repo", "success", SHA));
        trigger.handleSuccessfulRun(message(REPO, "failure", SHA));
        trigger.handleSuccessfulRun(message(REPO, "success", "bad-sha"));

        verify(coordinator, never()).start(any(), any(), any(), any(), any(), any());
    }

    private WorkflowRunMessage message(String repo, String conclusion, String sha) {
        return new WorkflowRunMessage(
                "delivery-1", 99L, repo, 123L, 1, sha, "main", 7,
                "completed", conclusion, "https://github.com/" + repo + "/actions/runs/123");
    }
}

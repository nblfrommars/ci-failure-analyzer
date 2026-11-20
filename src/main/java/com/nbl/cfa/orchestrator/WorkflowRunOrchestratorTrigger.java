package com.nbl.cfa.orchestrator;

import java.util.Map;

import com.nbl.cfa.webhook.WorkflowRunMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Khởi động nhánh deploy cho workflow_run thành công khi được bật rõ ràng. */
@Service
@ConditionalOnProperty(name = "cfa.orchestrator.enabled", havingValue = "true")
public class WorkflowRunOrchestratorTrigger {

    private final OrchestratorCoordinator coordinator;
    private final String demoRepo;
    private final String llmProfile;
    private final String llmModel;
    public WorkflowRunOrchestratorTrigger(
            OrchestratorCoordinator coordinator,
            @Value("${cfa.orchestrator.demo-repo:nblfrommars/ci-sandbox}") String demoRepo,
            @Value("${cfa.llm.profile:local}") String llmProfile,
            @Value("${cfa.llm.model:qwen3:4b}") String llmModel) {
        this.coordinator = coordinator;
        this.demoRepo = demoRepo;
        this.llmProfile = llmProfile;
        this.llmModel = llmModel;
    }

    public void handleSuccessfulRun(WorkflowRunMessage message) {
        if (!demoRepo.equals(message.repoFullName())
                || !"success".equals(message.conclusion())
                || message.headSha() == null
                || !message.headSha().matches("[0-9a-fA-F]{40}")) {
            return;
        }

        String taskId = "run-" + message.runId() + "-" + message.runAttempt();
        String image = "ghcr.io/nblfrommars/ci-sandbox:sha-" + message.headSha().toLowerCase();
        AgentTask task = new AgentTask(taskId, "deploy", Map.of(
                "tag", image,
                "env", "staging",
                "repo_full_name", message.repoFullName(),
                "github_run_id", message.runId(),
                "run_attempt", message.runAttempt()));

        OrchestratorContext context = coordinator.start(task, message.repoFullName(),
                message.runId(), message.runAttempt(), llmProfile, llmModel);
        context = coordinator.transition(context, OrchestratorEvent.START_DEPLOY,
                null, "accepted", null);
        coordinator.executeAgent(context, task, OrchestratorEvent.DEPLOY_SUCCEEDED,
                OrchestratorEvent.DEPLOY_FAILED);
    }
}

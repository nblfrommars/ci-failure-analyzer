package com.nbl.cfa.orchestrator;

import java.util.Map;

import com.nbl.cfa.repo.RepoConfig;
import com.nbl.cfa.repo.RepoRegistry;
import com.nbl.cfa.webhook.WorkflowRunMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Khởi động nhánh deploy cho workflow_run thành công khi được bật rõ ràng. */
@Service
@ConditionalOnProperty(name = "cfa.orchestrator.enabled", havingValue = "true")
public class WorkflowRunOrchestratorTrigger {

    private final OrchestratorCoordinator coordinator;
    private final OrchestratorPipeline pipeline;
    private final RepoRegistry repoRegistry;
    private final String imageWorkflowPath;
    private final String llmModel;
    public WorkflowRunOrchestratorTrigger(
            OrchestratorCoordinator coordinator,
            OrchestratorPipeline pipeline,
            RepoRegistry repoRegistry,
            @Value("${cfa.orchestrator.image-workflow-path:.github/workflows/build-image.yml}") String imageWorkflowPath,
            @Value("${cfa.llm.model:qwen3:4b}") String llmModel) {
        this.coordinator = coordinator;
        this.pipeline = pipeline;
        this.repoRegistry = repoRegistry;
        this.imageWorkflowPath = imageWorkflowPath;
        this.llmModel = llmModel;
    }

    public void handleSuccessfulRun(WorkflowRunMessage message) {
        RepoConfig repo = repoRegistry.find(message.repoFullName()).orElse(null);
        if (repo == null || !repo.enabled()
                || !repo.features().deploy() || !repo.features().test() || !repo.features().triage()
                || !"push".equals(message.workflowEvent())
                || !imageWorkflowPath.equals(message.workflowPath())
                || !repo.test().baseBranch().equals(message.headBranch())
                || !"success".equals(message.conclusion())
                || message.headSha() == null
                || !message.headSha().matches("[0-9a-fA-F]{40}")) {
            return;
        }

        String taskId = "run-" + message.runId() + "-" + message.runAttempt();
        String image = repo.deploy().imageRepository() + ":sha-" + message.headSha().toLowerCase();
        AgentTask task = new AgentTask(taskId, "deploy", Map.of(
                "tag", image,
                "env", "staging",
                "repo_full_name", message.repoFullName(),
                "github_run_id", message.runId(),
                "run_attempt", message.runAttempt()));

        OrchestratorContext context = coordinator.start(task, message.repoFullName(),
                message.runId(), message.runAttempt(), repo.llmProfile(), llmModel);
        AgentTask testTask = new AgentTask(taskId + "-test", "test", Map.of(
                "action", "generate_and_validate",
                "repo_full_name", message.repoFullName(),
                "staging_url", repo.deploy().stagingUrl(),
                "github_run_id", message.runId(),
                "run_attempt", message.runAttempt()));
        AgentTask triageTask = new AgentTask(taskId + "-triage", "triage", Map.of(
                "repo_full_name", message.repoFullName(),
                "run_id", message.runId(),
                "run_attempt", message.runAttempt()));
        AgentTask rollbackTask = new AgentTask(taskId + "-rollback", "deploy", Map.of(
                "action", "rollback", "env", "staging", "repo_full_name", message.repoFullName()));
        AgentTask healTask = new AgentTask(taskId + "-heal", "test", Map.of(
                "action", "heal", "heal_round", 1, "max_heal_rounds", 3,
                "repo_full_name", message.repoFullName()));

        pipeline.run(context, task, testTask, triageTask, rollbackTask, healTask);
    }
}

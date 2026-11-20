package com.nbl.cfa.worker;

import com.nbl.cfa.classifier.ClassificationResult;
import com.nbl.cfa.classifier.FailureClassifier;
import com.nbl.cfa.github.GitHubApiClient;
import com.nbl.cfa.messaging.RabbitConfig;
import com.nbl.cfa.persistence.PipelineStore;
import com.nbl.cfa.orchestrator.WorkflowRunOrchestratorTrigger;
import com.nbl.cfa.webhook.WorkflowRunMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consume hàng đợi workflow_run: lưu project + pipeline_run; tải log job, tiền xử lý, lưu job lỗi hoặc flaky,
 * rồi nhờ LLM phân loại (error_type, root_cause, key_evidence).
 */
@Component
public class WorkflowRunWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunWorker.class);

    private final JsonMapper jsonMapper;
    private final PipelineStore store;
    private final GitHubApiClient github;
    private final FailureClassifier classifier;
    private final ObjectProvider<WorkflowRunOrchestratorTrigger> orchestratorTrigger;

    public WorkflowRunWorker(JsonMapper jsonMapper, PipelineStore store,
                             GitHubApiClient github, FailureClassifier classifier,
                             ObjectProvider<WorkflowRunOrchestratorTrigger> orchestratorTrigger) {
        this.jsonMapper = jsonMapper;
        this.store = store;
        this.github = github;
        this.classifier = classifier;
        this.orchestratorTrigger = orchestratorTrigger;
    }

    @RabbitListener(queues = RabbitConfig.WORKFLOW_RUN_QUEUE)
    public void handle(String payload) {
        try {
            process(jsonMapper.readValue(payload, WorkflowRunMessage.class));
        } catch (Exception e) {
            log.error("Xử lý message workflow_run thất bại", e);
            // Không requeue để tránh vòng lặp vô hạn; sau này nên thêm dead-letter queue.
            throw new AmqpRejectAndDontRequeueException("Xử lý workflow_run thất bại", e);
        }
    }

    private void process(WorkflowRunMessage m) {
        long projectId = store.upsertProject(m.repoFullName(), m.installationId());
        long pipelineRunId = store.upsertPipelineRun(projectId, m);

        boolean runFailed = "failure".equals(m.conclusion());
        boolean runSucceeded = "success".equals(m.conclusion());
        if (!runFailed && !runSucceeded) {
            log.info("Lưu run {} ({}), conclusion={}, không cần lấy log",
                    m.runId(), m.repoFullName(), m.conclusion());
            return;
        }
        if (runSucceeded) {
            WorkflowRunOrchestratorTrigger trigger = orchestratorTrigger.getIfAvailable();
            if (trigger != null) {
                trigger.handleSuccessfulRun(m);
            }
        }
        if (m.installationId() == null) {
            throw new IllegalStateException("Message thiếu installationId, không gọi được GitHub API");
        }

        int saved = 0;
        for (GitHubApiClient.Job job : github.listJobs(
                m.installationId(), m.repoFullName(), m.runId(), m.runAttempt())) {
            boolean jobFailed = "failure".equals(job.conclusion());
            if (!jobFailed && !"success".equals(job.conclusion())) {
                continue;
            }
            String raw = github.downloadJobLog(m.installationId(), m.repoFullName(), job.id());
            LogPreprocessor.Result result = LogPreprocessor.process(raw);

            // Job lỗi thì luôn lưu; job xanh chỉ lưu khi có dấu hiệu flaky.
            if (jobFailed || result.flaky()) {
                long jobFailureId = store.upsertJobFailure(pipelineRunId, job.id(), job.name(),
                        result.failedStep(), result.excerpt(), result.flaky());
                saved++;
                classifyAndStore(jobFailureId, job, jobFailed, result);
            }
        }
        log.info("Run {} ({}): conclusion={}, đã lưu {} job (lỗi hoặc flaky)",
                m.runId(), m.repoFullName(), m.conclusion(), saved);
    }

    /** Phân loại bằng LLM. Lỗi ở bước này (ví dụ Ollama chưa chạy) không làm hỏng việc đã lưu ở trên. */
    private void classifyAndStore(long jobFailureId, GitHubApiClient.Job job, boolean jobFailed,
                                  LogPreprocessor.Result result) {
        try {
            ClassificationResult c = classifier.classify(new FailureClassifier.Input(
                    result.failedStep(), result.excerpt(), result.flaky(), jobFailed ? "failure" : "success"));
            store.updateClassification(jobFailureId, c.errorType().value(), c.rootCause(),
                    c.evidenceInLog() ? c.keyEvidence() : null);
            log.info("Job {}: error_type={} flaky={} evidenceInLog={}",
                    job.id(), c.errorType().value(), result.flaky(), c.evidenceInLog());
        } catch (Exception e) {
            log.warn("Không phân loại được job {} (Ollama đã chạy chưa?): {}", job.id(), e.toString());
        }
    }
}

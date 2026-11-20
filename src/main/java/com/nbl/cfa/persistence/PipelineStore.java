package com.nbl.cfa.persistence;

import com.nbl.cfa.webhook.WorkflowRunMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Ghi project / pipeline_run / job_failure. Tất cả đều là upsert nên xử lý lại cùng một message không bị trùng.
 */
@Repository
public class PipelineStore {

    private final JdbcClient jdbc;

    public PipelineStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long upsertProject(String repoFullName, Long installationId) {
        return jdbc.sql("""
                        INSERT INTO project (repo_full_name, installation_id)
                        VALUES (:repo, :installationId)
                        ON CONFLICT (repo_full_name) DO UPDATE
                          SET installation_id = COALESCE(EXCLUDED.installation_id, project.installation_id)
                        RETURNING id
                        """)
                .param("repo", repoFullName)
                .param("installationId", installationId)
                .query(Long.class)
                .single();
    }

    public long upsertPipelineRun(long projectId, WorkflowRunMessage m) {
        return jdbc.sql("""
                        INSERT INTO pipeline_run (project_id, github_run_id, run_attempt, head_sha,
                                                  head_branch, pull_request_number, status, conclusion)
                        VALUES (:projectId, :runId, :attempt, :sha, :branch, :pr, :status, :conclusion)
                        ON CONFLICT ON CONSTRAINT uq_pipeline_run DO UPDATE
                          SET head_sha = EXCLUDED.head_sha,
                              head_branch = EXCLUDED.head_branch,
                              pull_request_number = EXCLUDED.pull_request_number,
                              status = EXCLUDED.status,
                              conclusion = EXCLUDED.conclusion
                        RETURNING id
                        """)
                .param("projectId", projectId)
                .param("runId", m.runId())
                .param("attempt", m.runAttempt())
                .param("sha", m.headSha())
                .param("branch", m.headBranch())
                .param("pr", m.pullRequestNumber())
                .param("status", m.status())
                .param("conclusion", m.conclusion())
                .query(Long.class)
                .single();
    }

    public long upsertJobFailure(long pipelineRunId, long githubJobId, String jobName,
                                 String failedStep, String cleanedLog, boolean flaky) {
        return jdbc.sql("""
                        INSERT INTO job_failure (pipeline_run_id, github_job_id, job_name,
                                                 failed_step, cleaned_log, is_flaky)
                        VALUES (:runId, :jobId, :name, :step, :log, :flaky)
                        ON CONFLICT ON CONSTRAINT uq_job_failure DO UPDATE
                          SET job_name = EXCLUDED.job_name,
                              failed_step = EXCLUDED.failed_step,
                              cleaned_log = EXCLUDED.cleaned_log,
                              is_flaky = EXCLUDED.is_flaky
                        RETURNING id
                        """)
                .param("runId", pipelineRunId)
                .param("jobId", githubJobId)
                .param("name", jobName)
                .param("step", failedStep)
                .param("log", cleanedLog)
                .param("flaky", flaky)
                .query(Long.class)
                .single();
    }

    public void updateClassification(long jobFailureId, String errorType, String rootCause, String keyEvidence) {
        jdbc.sql("""
                        UPDATE job_failure
                           SET error_type = :errorType,
                               root_cause = :rootCause,
                               key_evidence = :keyEvidence
                         WHERE id = :id
                        """)
                .param("errorType", errorType)
                .param("rootCause", rootCause)
                .param("keyEvidence", keyEvidence)
                .param("id", jobFailureId)
                .update();
    }
}
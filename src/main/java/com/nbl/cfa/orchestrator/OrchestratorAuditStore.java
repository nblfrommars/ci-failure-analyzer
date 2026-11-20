package com.nbl.cfa.orchestrator;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lưu state hiện tại và lịch sử transition của Orchestrator. */
@Repository
public class OrchestratorAuditStore {

    private final JdbcClient jdbc;

    public OrchestratorAuditStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long createRun(String taskId, String repoFullName, Long githubRunId,
                          Integer runAttempt, OrchestratorState initialState,
                          String llmProfile, String llmModel) {
        return jdbc.sql("""
                        INSERT INTO orchestrator_run
                            (task_id, repo_full_name, github_run_id, run_attempt,
                             state, llm_profile, llm_model)
                        VALUES (:taskId, :repo, :runId, :attempt, :state, :profile, :model)
                        RETURNING id
                        """)
                .param("taskId", taskId)
                .param("repo", repoFullName)
                .param("runId", githubRunId)
                .param("attempt", runAttempt)
                .param("state", initialState.name())
                .param("profile", llmProfile)
                .param("model", llmModel)
                .query(Long.class)
                .single();
    }

    public void updateState(long runId, OrchestratorState state) {
        jdbc.sql("""
                        UPDATE orchestrator_run
                           SET state = :state, updated_at = now()
                         WHERE id = :id
                        """)
                .param("state", state.name())
                .param("id", runId)
                .update();
    }

    public void recordEvent(long runId, OrchestratorState fromState,
                            OrchestratorEvent event, OrchestratorState toState,
                            String agentId, String status, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO orchestrator_event
                            (orchestrator_run_id, from_state, event, to_state,
                             agent_id, status, payload)
                        VALUES (:runId, :fromState, :event, :toState,
                                :agentId, :status, CAST(:payload AS jsonb))
                        """)
                .param("runId", runId)
                .param("fromState", fromState == null ? null : fromState.name())
                .param("event", event.name())
                .param("toState", toState.name())
                .param("agentId", agentId)
                .param("status", status)
                .param("payload", payloadJson == null ? "{}" : payloadJson)
                .update();
    }
}

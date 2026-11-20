CREATE TABLE orchestrator_run (
    id              BIGSERIAL PRIMARY KEY,
    task_id         VARCHAR(128) NOT NULL UNIQUE,
    repo_full_name  VARCHAR(255) NOT NULL,
    github_run_id   BIGINT,
    run_attempt     INT,
    state           VARCHAR(32) NOT NULL,
    llm_profile     VARCHAR(32),
    llm_model       VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orchestrator_event (
    id                  BIGSERIAL PRIMARY KEY,
    orchestrator_run_id BIGINT NOT NULL REFERENCES orchestrator_run (id),
    from_state          VARCHAR(32),
    event               VARCHAR(64) NOT NULL,
    to_state            VARCHAR(32) NOT NULL,
    agent_id            VARCHAR(64),
    status              VARCHAR(32),
    payload             JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_orchestrator_event_run_created
    ON orchestrator_event (orchestrator_run_id, created_at);

CREATE INDEX idx_orchestrator_run_state
    ON orchestrator_run (state);

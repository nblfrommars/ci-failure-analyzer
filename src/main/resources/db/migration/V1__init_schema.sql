CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE project (
    id                 BIGSERIAL PRIMARY KEY,
    repo_full_name     VARCHAR(255) NOT NULL UNIQUE,
    installation_id    BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pipeline_run (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT NOT NULL REFERENCES project (id),
    github_run_id       BIGINT NOT NULL,
    run_attempt         INT NOT NULL DEFAULT 1,
    head_sha            VARCHAR(40),
    head_branch         VARCHAR(255),
    pull_request_number INT,
    status              VARCHAR(32),
    conclusion          VARCHAR(32),
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pipeline_run UNIQUE (project_id, github_run_id, run_attempt)
);

CREATE TABLE job_failure (
    id               BIGSERIAL PRIMARY KEY,
    pipeline_run_id  BIGINT NOT NULL REFERENCES pipeline_run (id),
    github_job_id    BIGINT NOT NULL,
    job_name         VARCHAR(255),
    cleaned_log      TEXT,
    error_type       VARCHAR(32),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_failure UNIQUE (pipeline_run_id, github_job_id)
);
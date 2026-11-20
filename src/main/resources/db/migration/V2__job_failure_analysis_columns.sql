ALTER TABLE job_failure
    ADD COLUMN failed_step   VARCHAR(255),
    ADD COLUMN is_flaky      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN root_cause    TEXT,
    ADD COLUMN key_evidence  TEXT;

ALTER TABLE job_failure
    ADD CONSTRAINT ck_job_failure_error_type
    CHECK (error_type IS NULL
           OR error_type IN ('test', 'build', 'dependency', 'config_env', 'lint', 'deploy', 'other'));
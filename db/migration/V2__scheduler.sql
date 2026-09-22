-- Scheduling lives on jobs.task itself
ALTER TABLE jobs.task
    ADD COLUMN step         smallint    NOT NULL DEFAULT 1,
    ADD COLUMN attempts     INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_run_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD COLUMN locked_by    TEXT;

-- the claim query never scans closed tasks
CREATE INDEX task_due_idx ON jobs.task (next_run_at) WHERE state = 1;

CREATE SCHEMA IF NOT EXISTS jobs;

-- main table for state machine - contains main info through the job flow
CREATE TABLE jobs.task
(
    request_id  TEXT PRIMARY KEY,     -- idempotency key from caller
    machine_id  smallint    NOT NULL,
    state       smallint    NOT NULL, -- OPEN 1 | CLOSED 0
    context     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX task_idempotency_key ON jobs.task (machine_id, request_id) WHERE state = 1;

-- state history with errors on dedicated state (if exists)
CREATE TABLE jobs.task_state
(
    request_id    TEXT        NOT NULL REFERENCES jobs.task (request_id),
    state         smallint    NOT NULL,
    error_message TEXT NULL,
    attempts      INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX task_state_idx ON jobs.task_state (request_id, state);


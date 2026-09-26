-- Events the PSP delivered that cannot be booked: the data is wrong or
-- contradicts what we stored. The money has already moved, so rejecting them
-- only makes the PSP retry for days. We keep the raw event and answer 200.
--
-- Not the ledger: no entries, no balances. An inbox for a human.
--
-- Every row is an open problem. Resolving one deletes it, so there is no
-- status to keep in step and the whole table is the review queue.
--
-- Rule: store only what a retry cannot fix. HELD is for money we can book,
-- processing_error is for events we cannot book.

CREATE TABLE mor.processing_error
(
    id                 uuid        PRIMARY KEY,
    event_type         smallint    NOT NULL,              -- EventType: 1 CAPTURE, 2 REFUND, 3 PAYOUT, 4 TAX_REMITTANCE
    external_reference text        NOT NULL,              -- pspReference or refundReference
    payload            jsonb       NOT NULL,              -- raw request, exactly as received
    error_code         smallint    NOT NULL,              -- ProcessingErrorCode
    error_detail       text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT processing_error_event_ck CHECK (event_type IN (1, 2, 3, 4)),
    CONSTRAINT processing_error_code_ck  CHECK (error_code IN (1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12))
);

-- A PSP retry of the same broken event must not add a second row.
CREATE UNIQUE INDEX processing_error_uk
    ON mor.processing_error (event_type, external_reference, error_code);

-- Oldest first: the review queue, and alerting on anything unresolved too long.
CREATE INDEX processing_error_created_idx ON mor.processing_error (created_at);

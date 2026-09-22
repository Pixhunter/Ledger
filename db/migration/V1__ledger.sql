-- The ledger proper: immutable, append-only, double-entry.
--
-- No balance columns anywhere. A balance is SUM(amount) over this table, so
-- writes are pure INSERTs and never contend on a row - which matters because
-- mor:cash and mor:revenue are touched by every single capture.
--
-- Sign convention: debits positive, credits negative, and the entries of one
-- transaction must sum to zero. A liability account therefore carries a
-- negative balance, and "what we owe" is -balance.

CREATE SCHEMA IF NOT EXISTS ledger;

CREATE TABLE ledger.entry
(
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    transaction_id TEXT        NOT NULL,  -- groups the entries of one event
    request_id     TEXT        NOT NULL,  -- PSP event id
    kind           TEXT        NOT NULL,  -- CAPTURE | REFUND | REMITTANCE | PAYOUT

    account        TEXT        NOT NULL,  -- mor:cash, tax:DE:payable, merchant:{id}:payable
    amount         BIGINT      NOT NULL,  -- minor units, signed
    currency       TEXT        NOT NULL,  -- ISO 4217; never mixed in one balance

    -- Denormalised from the account name so tax reports do not parse strings.
    jurisdiction   TEXT,

    -- The tax point: when the sale happened, which decides the filing period.
    -- Not created_at, which is merely when we wrote the row.
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency at the ledger level: replaying a capture cannot double-post,
-- independently of whatever the caller did.
CREATE UNIQUE INDEX entry_idempotency_idx
    ON ledger.entry (request_id, account);

-- Balance queries: SUM(amount) per account per currency.
CREATE INDEX entry_account_idx
    ON ledger.entry (account, currency);

-- Tax reports: what we owe a jurisdiction, optionally bounded by period.
CREATE INDEX entry_jurisdiction_idx
    ON ledger.entry (jurisdiction, currency, occurred_at)
    WHERE jurisdiction IS NOT NULL;

CREATE INDEX entry_transaction_idx
    ON ledger.entry (transaction_id);

-- Convenience view: the "taxes table". It is a projection, not a second
-- source of truth - storing tax separately from the ledger would let the two
-- disagree, and then neither is trustworthy.
CREATE VIEW ledger.tax_liability AS
SELECT jurisdiction,
       currency,
       -SUM(amount) AS owed          -- liabilities are credits, so negate
FROM ledger.entry
WHERE account LIKE 'tax:%'
GROUP BY jurisdiction, currency;

CREATE VIEW ledger.merchant_balance AS
SELECT split_part(account, ':', 2) AS merchant_id,
       currency,
       -SUM(amount)                 AS owed
FROM ledger.entry
WHERE account LIKE 'merchant:%'
GROUP BY split_part(account, ':', 2), currency;

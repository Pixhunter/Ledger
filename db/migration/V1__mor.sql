-- MoR ledger schema.
CREATE SCHEMA IF NOT EXISTS mor;

-- ---------------------------------------------------------------------------
-- merchant: the business we sell on behalf of.
-- One merchant = one legal company. Entities in two countries are two rows.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.merchant
(
    id           uuid        PRIMARY KEY,
    name         text        NOT NULL,
    currency     text        NOT NULL,              -- ISO 4217, payout currency
    fee_rate_bps int         NOT NULL,              -- 300 = 3%; frozen per payment
    tax_category smallint    NOT NULL,              -- TaxCategory: 1 STANDARD, 2 REDUCED
    status       smallint    NOT NULL,              -- MerchantStatus: 1 ACTIVE, 2 SUSPENDED
    created_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT merchant_currency_ck     CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT merchant_fee_rate_ck     CHECK (fee_rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT merchant_tax_category_ck CHECK (tax_category IN (1, 2)),
    CONSTRAINT merchant_status_ck       CHECK (status IN (1, 2))
);


-- ---------------------------------------------------------------------------
-- merchant_payment_details: where payouts go. 1:1 with merchant.
--
-- Separate table because it is sensitive: encrypted and access-restricted in
-- production, and no ledger or balance query ever needs to touch it.
-- Nullable bank identifiers because the required set differs by country:
-- EU/UK need IBAN + BIC, US needs account + routing, AU needs account + BSB.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.merchant_payment_details
(
    merchant_id    uuid        PRIMARY KEY REFERENCES mor.merchant (id),
    psp_account_id text        NOT NULL,             -- the PSP's id for the payout destination; we never send ours
    account_holder text        NOT NULL,             -- every bank transfer needs it
    iban           text        NULL,                 -- EU / UK
    bic            text        NULL,
    account_number text        NULL,                 -- US / AU
    routing_code   text        NULL,                 -- US routing number / AU BSB
    bank_country   text        NOT NULL,             -- may differ from merchant currency
    address        jsonb       NOT NULL,             -- holder address, international transfers
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT mpd_bank_country_ck CHECK (bank_country ~ '^[A-Z]{2}$'),
    -- Enough to actually pay someone: IBAN, or account + routing.
    CONSTRAINT mpd_identifiers_ck CHECK (
        iban IS NOT NULL OR (account_number IS NOT NULL AND routing_code IS NOT NULL)
    )
);


-- ---------------------------------------------------------------------------
-- tax_rate: reference data, ~150 countries x 2 categories.
-- Loaded into memory at startup; never queried per payment.
--
-- Append-only: a rate change is a new row with a future valid_from, so old
-- rows stay for audits and a past payment can always be re-explained.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.tax_rate
(
    country    text NOT NULL,                        -- ISO 3166-1 alpha-2
    category   smallint NOT NULL,                    -- TaxCategory: 1 STANDARD, 2 REDUCED
    valid_from date NOT NULL,                        -- rate applies from this date
    rate_bps   int  NOT NULL,                        -- 1900 = 19%

    PRIMARY KEY (country, category, valid_from),

    CONSTRAINT tax_rate_country_ck  CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT tax_rate_category_ck CHECK (category IN (1, 2)),
    CONSTRAINT tax_rate_bps_ck      CHECK (rate_bps BETWEEN 0 AND 10000)
);


-- ---------------------------------------------------------------------------
-- payment: one row per payment reported by the PSP. The business fact - what the
-- customer paid and how it split. Money movement lives in ledger_entry.
--
-- Everything except status is frozen when recorded: amounts, rate, category,
-- country. A later rate change or a merchant moving country must never
-- rewrite history.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.payment
(
    id             uuid        PRIMARY KEY,
    psp_reference  text        NOT NULL,             -- idempotency key, PSP's own id
    merchant_id    uuid        NULL,
    gross          numeric(19,4) NOT NULL,            -- major units, customer paid, tax included
    tax            numeric(19,4) NOT NULL,
    fee            numeric(19,4) NOT NULL,            -- MoR revenue
    merchant_net   numeric(19,4) NOT NULL,
    currency       text        NOT NULL,
    tax_country    text        NULL,                 -- result of the evidence vote
    tax_category   smallint    NULL,                 -- TaxCategory
    tax_rate_bps   int         NULL,                 -- frozen when recorded
    reverse_charge boolean     NOT NULL DEFAULT false,
    evidence       jsonb       NOT NULL,             -- {"billing":"ES","card":"AU","ip":"ES","vatId":null}
    status         smallint    NOT NULL,             -- PaymentStatus: 1 POSTED
    payment_time   timestamptz NOT NULL,             -- from the PSP: the tax point
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT payment_split_ck    CHECK (gross = tax + fee + merchant_net),
    CONSTRAINT payment_amounts_ck  CHECK (gross > 0 AND tax >= 0 AND fee >= 0 AND merchant_net >= 0),
    CONSTRAINT payment_status_ck   CHECK (status = 1),
    CONSTRAINT payment_currency_ck CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payment_country_ck  CHECK (tax_country IS NULL OR tax_country ~ '^[A-Z]{2}$')
);

CREATE UNIQUE INDEX payment_psp_reference_uk ON mor.payment (psp_reference);
CREATE INDEX payment_merchant_idx ON mor.payment (merchant_id, payment_time);

-- Hold state is independent from the payment lifecycle. A payment may have
-- several reasons, resolved separately.
CREATE TABLE mor.payment_hold
(
    payment_id  uuid        NOT NULL REFERENCES mor.payment (id),
    reason      smallint    NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz NULL,

    PRIMARY KEY (payment_id, reason),
    CONSTRAINT payment_hold_reason_ck CHECK (reason IN (1, 2))
);

CREATE INDEX payment_hold_active_idx
    ON mor.payment_hold (payment_id)
    WHERE resolved_at IS NULL;


-- ---------------------------------------------------------------------------
-- ledger_transaction: one money event.
--
-- Split from ledger_entry so the type and payment id are stored once rather
-- than repeated on every line. Both inserts still go in one statement.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.ledger_transaction
(
    id         uuid        PRIMARY KEY,
    type       smallint    NOT NULL,                 -- LedgerTransactionType: 1 CAPTURE, 2 REFUND, 3 RELEASE, 4 PAYOUT
    payment_id uuid        NULL REFERENCES mor.payment (id),   -- null for PAYOUT
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ledger_transaction_type_ck CHECK (type IN (1, 2, 3, 4))
);

CREATE INDEX ledger_transaction_payment_idx ON mor.ledger_transaction (payment_id);


-- ---------------------------------------------------------------------------
-- ledger_entry: one line = one change in whose money it is.
--
-- Append-only. No UPDATE, no DELETE - a mistake is corrected with a new
-- transaction. That is also why hot accounts do not lock: every capture
-- touches TAX:DE and REVENUE, but only ever by INSERT.
--
-- A ledger account is not a bank account. All money physically sits in the
-- PSP balance; the account is a label saying who it belongs to.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.ledger_entry
(
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id uuid   NOT NULL REFERENCES mor.ledger_transaction (id),
    purpose        smallint NOT NULL,                -- PaymentPurpose: 1 PSP, 2 TAX, 3 REVENUE, 4 MERCHANT, 5 HELD, 6 SUSPENSE
    purpose_key    text   NULL,                      -- TAX: country, MERCHANT/HELD/SUSPENSE: merchant id
    amount         numeric(19,4) NOT NULL,           -- major units, signed: + debit, - credit
    currency       text   NOT NULL,
    -- When the money moved, not when we wrote the row: the tax point for a
    -- capture, refundedAt for a refund. Copied onto the entry so a balance is
    -- one index scan with no join to ledger_transaction. Safe to denormalise:
    -- entries are append-only and never updated.
    occurred_at    timestamptz NOT NULL,
    settled_by_transaction_id uuid NULL REFERENCES mor.ledger_transaction (id),

    CONSTRAINT ledger_entry_amount_ck   CHECK (amount <> 0),
    CONSTRAINT ledger_entry_purpose_ck  CHECK (purpose IN (1, 2, 3, 4, 5, 6)),
    CONSTRAINT ledger_entry_currency_ck CHECK (currency ~ '^[A-Z]{3}$')
);

-- Balances: sum one account, optionally up to a point in time.
CREATE INDEX ledger_entry_balance_idx
    ON mor.ledger_entry (purpose, purpose_key, currency, occurred_at);
CREATE INDEX ledger_entry_transaction_idx ON mor.ledger_entry (transaction_id);
CREATE INDEX ledger_entry_settlement_transaction_idx
    ON mor.ledger_entry (settled_by_transaction_id)
    WHERE settled_by_transaction_id IS NOT NULL;
CREATE INDEX ledger_entry_unsettled_merchant_idx
    ON mor.ledger_entry (purpose, purpose_key, currency, id)
    WHERE settled_by_transaction_id IS NULL;

-- ---------------------------------------------------------------------------
-- payout: one per merchant per business day.
--
-- The composite primary key IS the idempotency guarantee: a rerun, or two job
-- instances racing, cannot pay the same merchant twice for the same date.
-- The job lock is only an optimisation.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.payout
(
    merchant_id           uuid        NOT NULL REFERENCES mor.merchant (id),
    payout_date           date        NOT NULL,
    amount                numeric(19,4) NOT NULL,
    currency              text        NOT NULL,
    ledger_transaction_id uuid        NOT NULL REFERENCES mor.ledger_transaction (id),
    status                smallint    NOT NULL,      -- PayoutStatus: 1 COMPUTED, 2 SENT, 3 PROCESSING
    psp_reference         text        NULL,          -- the PSP's id for the transfer, set when sent
    claimed_at            timestamptz NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (merchant_id, payout_date),

    CONSTRAINT payout_amount_ck   CHECK (amount > 0),
    CONSTRAINT payout_status_ck   CHECK (status IN (1, 2, 3)),
    CONSTRAINT payout_currency_ck CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX payout_date_idx ON mor.payout (payout_date);
CREATE UNIQUE INDEX payout_ledger_transaction_uk ON mor.payout (ledger_transaction_id);
CREATE INDEX payout_due_idx ON mor.payout (status, claimed_at, payout_date, merchant_id);


-- ---------------------------------------------------------------------------
-- merchant_daily_balance: what each merchant was owed at the end of a business
-- day, before payout. Append-only, written by the payout calculation for every
-- merchant - positive, zero or negative.
--
-- The ledger stays the source of truth; this is a snapshot. It exists so
-- "negative for N days in a row" is one indexed read instead of replaying
-- every entry.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.merchant_daily_balance
(
    merchant_id  uuid          NOT NULL REFERENCES mor.merchant (id),
    balance_date date          NOT NULL,             -- London business date
    balance      numeric(19,4) NOT NULL,             -- signed, before payout
    currency     text          NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),

    PRIMARY KEY (merchant_id, balance_date),

    CONSTRAINT mdb_currency_ck CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX merchant_daily_balance_idx
    ON mor.merchant_daily_balance (merchant_id, balance_date DESC);


-- ---------------------------------------------------------------------------
-- tax_remittance: what was filed and paid to one country's tax authority for
-- one filing period.
--
-- Per country per period, not per payment: a tax authority is paid once a
-- month against a return, and refunds inside the period simply reduce the
-- balance before it is remitted.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.tax_remittance
(
    country               text          NOT NULL,      -- ISO 3166-1 alpha-2
    period_start          date          NOT NULL,      -- first day of the filing month
    amount                numeric(19,4) NOT NULL,
    currency              text          NOT NULL,
    ledger_transaction_id uuid          NOT NULL REFERENCES mor.ledger_transaction (id),
    status                smallint      NOT NULL,      -- PayoutStatus: 1 COMPUTED, 2 SENT, 3 PROCESSING
    reference             text          NULL,          -- the authority's id for the payment
    claimed_at            timestamptz   NULL,
    created_at            timestamptz   NOT NULL DEFAULT now(),

    PRIMARY KEY (country, period_start),

    CONSTRAINT tax_remittance_amount_ck   CHECK (amount > 0),
    CONSTRAINT tax_remittance_status_ck   CHECK (status IN (1, 2, 3)),
    CONSTRAINT tax_remittance_country_ck  CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT tax_remittance_currency_ck CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE UNIQUE INDEX tax_remittance_ledger_transaction_uk
    ON mor.tax_remittance (ledger_transaction_id);
CREATE INDEX tax_remittance_due_idx
    ON mor.tax_remittance (status, claimed_at, period_start, country);


-- ---------------------------------------------------------------------------
-- tax_daily_balance: what each country's tax account stood at, end of day.
--
-- Negative means we remitted more than we owed: a refund arrived after the
-- period was filed. That is normal for a day or two and settles itself from
-- the next sales. Staying negative means the refunds are not coming back.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.tax_daily_balance
(
    country      text          NOT NULL,
    balance_date date          NOT NULL,
    balance      numeric(19,4) NOT NULL,             -- signed, what we still owe
    currency     text          NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),

    PRIMARY KEY (country, balance_date),

    CONSTRAINT tdb_country_ck  CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT tdb_currency_ck CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX tax_daily_balance_idx ON mor.tax_daily_balance (country, balance_date DESC);

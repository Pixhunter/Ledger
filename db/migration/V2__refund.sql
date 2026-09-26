-- Refund flow.
ALTER TABLE mor.payment DROP CONSTRAINT payment_status_ck;
ALTER TABLE mor.payment ADD CONSTRAINT payment_status_ck CHECK (status IN (1, 2, 4, 5));
ALTER TABLE mor.payment DROP CONSTRAINT payment_hold_ck;
ALTER TABLE mor.payment ADD CONSTRAINT payment_hold_ck CHECK (status <> 2 OR hold_reason IS NOT NULL);


-- ---------------------------------------------------------------------------
-- refund: one row per successful refund. Many refunds per payment.
--
-- No status column on purpose. success = false from the PSP means the refund
-- did not happen, so there is nothing to record - it is logged and dropped.
-- Every row in this table is money that has already left.
-- ---------------------------------------------------------------------------
CREATE TABLE mor.refund
(
    id               uuid        PRIMARY KEY,
    refund_reference text        NOT NULL,           -- idempotency key, the refund's own PSP id
    payment_id       uuid        NOT NULL REFERENCES mor.payment (id),
    amount           numeric(19,4) NOT NULL,
    currency         text        NOT NULL,
    reason           smallint    NOT NULL,           -- RefundReason: 1 DUPLICATE, 2 FRAUD, 3 CUSTOMER_REQUEST, 4 PRODUCT_ISSUE, 5 OTHER
    fee_returned     boolean     NOT NULL,           -- frozen: did the MoR give its fee back
    refunded_at      timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT refund_amount_ck   CHECK (amount > 0),
    CONSTRAINT refund_currency_ck CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT refund_reason_ck   CHECK (reason IN (1, 2, 3, 4, 5))
);

CREATE UNIQUE INDEX refund_reference_uk ON mor.refund (refund_reference);
CREATE INDEX refund_payment_idx ON mor.refund (payment_id);

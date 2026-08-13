-- V1: the schema as it stands at the end of Phase 1.
--
-- Generated from the JPA entities by Hibernate against PostgreSQL 16, then
-- committed verbatim so the schema stops being a runtime side effect and
-- becomes a reviewable artefact. From here on Hibernate runs with
-- ddl-auto=validate: it compares the entities against this and refuses to start
-- if they disagree, so a forgotten migration fails loudly at boot rather than
-- as a missing column at 3am.
--
-- The CHECK constraints on enum columns are Hibernate's doing and worth
-- keeping: they stop an invalid status reaching a table even if a bug or a
-- manual UPDATE tries to write one.

CREATE TABLE disputes (
    amount bigint NOT NULL,
    created_at timestamp(6) with time zone,
    dispute_fee bigint NOT NULL,
    evidence_due_by timestamp(6) with time zone,
    resolved_at timestamp(6) with time zone,
    evidence_json character varying(4000),
    category character varying(255),
    currency character varying(255),
    id character varying(255) NOT NULL,
    merchant_id character varying(255),
    payment_intent_id character varying(255),
    reason_code character varying(255),
    reason_description character varying(255),
    status character varying(255),
    CONSTRAINT disputes_status_check CHECK (((status)::text = ANY ((ARRAY['NEEDS_RESPONSE'::character varying, 'UNDER_REVIEW'::character varying, 'WON'::character varying, 'LOST'::character varying, 'ACCEPTED'::character varying])::text[])))
);
CREATE TABLE idempotency_records (
    response_status integer,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    response_body character varying(8000),
    id character varying(255) NOT NULL,
    idempotency_key character varying(255),
    merchant_id character varying(255),
    request_fingerprint character varying(255),
    resource_id character varying(255),
    state character varying(255),
    CONSTRAINT idempotency_records_state_check CHECK (((state)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'COMPLETED'::character varying])::text[])))
);
CREATE TABLE ledger_entries (
    amount bigint NOT NULL,
    created_at timestamp(6) with time zone,
    account character varying(255),
    currency character varying(255),
    direction character varying(255),
    id character varying(255) NOT NULL,
    journal_id character varying(255),
    memo character varying(255),
    merchant_id character varying(255),
    ref_id character varying(255),
    ref_type character varying(255),
    CONSTRAINT ledger_entries_account_check CHECK (((account)::text = ANY ((ARRAY['SCHEME_RECEIVABLE'::character varying, 'MERCHANT_PAYABLE'::character varying, 'FEE_INCOME'::character varying, 'REFUND_PAYABLE'::character varying, 'DISPUTE_HOLDING'::character varying, 'SETTLEMENT_CASH'::character varying])::text[]))),
    CONSTRAINT ledger_entries_direction_check CHECK (((direction)::text = ANY ((ARRAY['DEBIT'::character varying, 'CREDIT'::character varying])::text[])))
);
CREATE TABLE merchants (
    created_at timestamp(6) with time zone,
    country character varying(255),
    id character varying(255) NOT NULL,
    mcc character varying(255),
    name character varying(255),
    publishable_key character varying(255),
    secret_key character varying(255),
    settlement_currency character varying(255),
    webhook_secret character varying(255),
    webhook_url character varying(255)
);
CREATE TABLE payment_intents (
    risk_score integer,
    amount bigint NOT NULL,
    amount_capturable bigint NOT NULL,
    amount_received bigint NOT NULL,
    amount_refunded bigint NOT NULL,
    application_fee bigint NOT NULL,
    canceled_at timestamp(6) with time zone,
    captured_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    version bigint NOT NULL,
    next_action_url character varying(2000),
    metadata_json character varying(4000),
    acquirer_id character varying(255),
    authorization_code character varying(255),
    capture_method character varying(255),
    client_secret character varying(255),
    currency character varying(255),
    customer_ref character varying(255),
    description character varying(255),
    id character varying(255) NOT NULL,
    last_decline_code character varying(255),
    last_error_code character varying(255),
    last_error_message character varying(255),
    merchant_id character varying(255),
    network_transaction_id character varying(255),
    next_action_type character varying(255),
    payment_method_id character varying(255),
    payment_method_type character varying(255),
    risk_level character varying(255),
    statement_descriptor character varying(255),
    status character varying(255),
    three_ds_eci character varying(255),
    three_ds_status character varying(255),
    CONSTRAINT payment_intents_capture_method_check CHECK (((capture_method)::text = ANY ((ARRAY['AUTOMATIC'::character varying, 'MANUAL'::character varying])::text[]))),
    CONSTRAINT payment_intents_payment_method_type_check CHECK (((payment_method_type)::text = ANY ((ARRAY['CARD'::character varying, 'UPI'::character varying, 'WALLET'::character varying, 'NETBANKING'::character varying])::text[]))),
    CONSTRAINT payment_intents_status_check CHECK (((status)::text = ANY ((ARRAY['REQUIRES_PAYMENT_METHOD'::character varying, 'REQUIRES_CONFIRMATION'::character varying, 'REQUIRES_ACTION'::character varying, 'PROCESSING'::character varying, 'REQUIRES_CAPTURE'::character varying, 'SUCCEEDED'::character varying, 'CANCELED'::character varying, 'FAILED'::character varying])::text[])))
);
CREATE TABLE payment_methods (
    card_exp_month integer,
    card_exp_year integer,
    created_at timestamp(6) with time zone,
    card_bin character varying(255),
    card_brand character varying(255),
    card_country character varying(255),
    card_fingerprint character varying(255),
    card_funding character varying(255),
    card_issuer character varying(255),
    card_last4 character varying(255),
    id character varying(255) NOT NULL,
    merchant_id character varying(255),
    network_token character varying(255),
    simulated_behaviour character varying(255),
    type character varying(255),
    upi_vpa character varying(255),
    wallet_provider character varying(255),
    CONSTRAINT payment_methods_type_check CHECK (((type)::text = ANY ((ARRAY['CARD'::character varying, 'UPI'::character varying, 'WALLET'::character varying, 'NETBANKING'::character varying])::text[])))
);
CREATE TABLE pending_actions (
    consumed boolean NOT NULL,
    otp_attempts integer NOT NULL,
    created_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone,
    expected_otp character varying(255),
    id character varying(255) NOT NULL,
    kind character varying(255),
    merchant_id character varying(255),
    payment_intent_id character varying(255),
    return_url character varying(255),
    CONSTRAINT pending_actions_kind_check CHECK (((kind)::text = ANY ((ARRAY['THREE_DS_CHALLENGE'::character varying, 'UPI_COLLECT'::character varying, 'WALLET_REDIRECT'::character varying])::text[])))
);
CREATE TABLE refunds (
    amount bigint NOT NULL,
    created_at timestamp(6) with time zone,
    settled_at timestamp(6) with time zone,
    currency character varying(255),
    failure_reason character varying(255),
    id character varying(255) NOT NULL,
    merchant_id character varying(255),
    payment_intent_id character varying(255),
    reason character varying(255),
    rrn character varying(255),
    status character varying(255),
    CONSTRAINT refunds_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying, 'REVERSED'::character varying])::text[])))
);
CREATE TABLE settlements (
    expected_payout_date date,
    period_end date,
    period_start date,
    transaction_count integer NOT NULL,
    created_at timestamp(6) with time zone,
    dispute_amount bigint NOT NULL,
    fee_amount bigint NOT NULL,
    gross_amount bigint NOT NULL,
    net_amount bigint NOT NULL,
    paid_at timestamp(6) with time zone,
    refund_amount bigint NOT NULL,
    currency character varying(255),
    id character varying(255) NOT NULL,
    merchant_id character varying(255),
    status character varying(255),
    CONSTRAINT settlements_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'PENDING_PAYOUT'::character varying, 'PAID'::character varying, 'FAILED'::character varying])::text[])))
);
CREATE TABLE transactions (
    amount bigint NOT NULL,
    created_at timestamp(6) with time zone,
    latency_ms bigint NOT NULL,
    request_dump character varying(4000),
    response_dump character varying(4000),
    acquirer_id character varying(255),
    auth_code character varying(255),
    currency character varying(255),
    id character varying(255) NOT NULL,
    merchant_id character varying(255),
    mti character varying(255),
    outcome character varying(255),
    payment_intent_id character varying(255),
    processing_code character varying(255),
    rail_name character varying(255),
    response_code character varying(255),
    response_text character varying(255),
    rrn character varying(255),
    stan character varying(255),
    type character varying(255),
    CONSTRAINT transactions_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['APPROVED'::character varying, 'DECLINED'::character varying, 'PENDING'::character varying, 'ERROR'::character varying])::text[]))),
    CONSTRAINT transactions_type_check CHECK (((type)::text = ANY ((ARRAY['AUTHORIZATION'::character varying, 'CAPTURE'::character varying, 'VOID'::character varying, 'REFUND'::character varying, 'AUTHENTICATION'::character varying, 'COLLECT'::character varying, 'INQUIRY'::character varying])::text[])))
);
CREATE TABLE webhook_events (
    attempts integer NOT NULL,
    last_response_status integer,
    created_at timestamp(6) with time zone,
    delivered_at timestamp(6) with time zone,
    next_attempt_at timestamp(6) with time zone,
    last_error character varying(1000),
    payload_json character varying(8000),
    destination_url character varying(255),
    id character varying(255) NOT NULL,
    merchant_id character varying(255),
    status character varying(255),
    type character varying(255),
    CONSTRAINT webhook_events_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'DELIVERED'::character varying, 'RETRYING'::character varying, 'DEAD'::character varying])::text[])))
);
ALTER TABLE ONLY disputes
    ADD CONSTRAINT disputes_pkey PRIMARY KEY (id);
ALTER TABLE ONLY idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);
ALTER TABLE ONLY ledger_entries
    ADD CONSTRAINT ledger_entries_pkey PRIMARY KEY (id);
ALTER TABLE ONLY merchants
    ADD CONSTRAINT merchants_pkey PRIMARY KEY (id);
ALTER TABLE ONLY merchants
    ADD CONSTRAINT merchants_publishable_key_key UNIQUE (publishable_key);
ALTER TABLE ONLY merchants
    ADD CONSTRAINT merchants_secret_key_key UNIQUE (secret_key);
ALTER TABLE ONLY payment_intents
    ADD CONSTRAINT payment_intents_pkey PRIMARY KEY (id);
ALTER TABLE ONLY payment_methods
    ADD CONSTRAINT payment_methods_pkey PRIMARY KEY (id);
ALTER TABLE ONLY pending_actions
    ADD CONSTRAINT pending_actions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY refunds
    ADD CONSTRAINT refunds_pkey PRIMARY KEY (id);
ALTER TABLE ONLY settlements
    ADD CONSTRAINT settlements_pkey PRIMARY KEY (id);
ALTER TABLE ONLY transactions
    ADD CONSTRAINT transactions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY webhook_events
    ADD CONSTRAINT webhook_events_pkey PRIMARY KEY (id);
CREATE INDEX idx_ledger_account ON ledger_entries USING btree (account);
CREATE INDEX idx_ledger_journal ON ledger_entries USING btree (journal_id);
CREATE INDEX idx_ledger_ref ON ledger_entries USING btree (ref_id);
CREATE INDEX idx_webhook_dispatch ON webhook_events USING btree (status, next_attempt_at);

-- ---------------------------------------------------------------------------
-- Indexes for the access patterns the repositories actually use.
--
-- Hibernate only creates indexes declared with @Index, which covered the ledger
-- and the webhook dispatcher. Everything below backs a finder method that would
-- otherwise sequential-scan. Almost every one leads with merchant_id, because
-- every query in this system is tenant-scoped first and filtered second.
-- ---------------------------------------------------------------------------

-- PaymentIntentRepository
CREATE INDEX idx_pi_merchant_created ON payment_intents (merchant_id, created_at DESC);
CREATE INDEX idx_pi_merchant_status  ON payment_intents (merchant_id, status, created_at DESC);
-- Drives the sweeper that voids abandoned authorisations.
CREATE INDEX idx_pi_status_created   ON payment_intents (status, created_at);
-- Settlement batches select captured payments in a date window.
CREATE INDEX idx_pi_settlement       ON payment_intents (merchant_id, status, captured_at);

-- TransactionRepository
CREATE INDEX idx_txn_intent   ON transactions (payment_intent_id, created_at);
CREATE INDEX idx_txn_merchant ON transactions (merchant_id, created_at DESC);

-- RefundRepository
CREATE INDEX idx_refund_intent ON refunds (payment_intent_id, created_at);
CREATE INDEX idx_refund_batch  ON refunds (merchant_id, status, created_at);

-- DisputeRepository
CREATE INDEX idx_dispute_merchant ON disputes (merchant_id, created_at DESC);
CREATE INDEX idx_dispute_intent   ON disputes (payment_intent_id);

-- SettlementRepository
CREATE INDEX idx_settlement_merchant ON settlements (merchant_id, created_at DESC);

-- PendingActionRepository: the open-action lookup and the expiry sweeper.
-- Partial indexes, because consumed actions are dead weight for both queries.
CREATE INDEX idx_action_intent ON pending_actions (payment_intent_id) WHERE consumed = false;
CREATE INDEX idx_action_expiry ON pending_actions (expires_at) WHERE consumed = false;

-- WebhookEventRepository
CREATE INDEX idx_event_merchant ON webhook_events (merchant_id, created_at DESC);

-- LedgerEntryRepository
CREATE INDEX idx_ledger_merchant ON ledger_entries (merchant_id, created_at DESC);

-- IdempotencyService purges by age on a schedule.
CREATE INDEX idx_idem_created ON idempotency_records (created_at);

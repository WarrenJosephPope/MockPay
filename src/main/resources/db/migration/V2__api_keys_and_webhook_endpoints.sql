-- V2: move API keys and webhook destinations off the merchants table.
--
-- Until now a merchant had exactly one secret key, one publishable key, one webhook URL and one
-- signing secret, all as plaintext columns. That blocks everything a dashboard needs: issuing a
-- second key so the first can be rotated without downtime, revoking a leaked key, sending different
-- event types to different services, and — most importantly — not storing secret keys in a form
-- that a database dump hands straight to an attacker.

-- ---------------------------------------------------------------------------
-- api_keys
--
-- key_hash is SHA-256 of the full key, and it is what authentication looks up.
--
-- Deliberately NOT bcrypt or Argon2. Those exist to make low-entropy secrets expensive to crack;
-- an API key is 32 characters from a CSPRNG, so there is nothing to guess. A slow hash here would
-- buy no security and would add its deliberate ~100ms to every authenticated request — and it
-- could not be indexed, forcing a scan-and-compare across every row on the hot path.
-- Passwords, arriving in a later phase, are a different problem and will get Argon2.
-- ---------------------------------------------------------------------------
CREATE TABLE api_keys (
    id            varchar(255) NOT NULL,
    merchant_id   varchar(255) NOT NULL,
    type          varchar(255) NOT NULL,
    key_hash      varchar(255) NOT NULL,
    key_prefix    varchar(255),
    -- Plaintext, for publishable keys only. Those are public by design and must stay displayable.
    -- NULL for every secret key, forever.
    public_value  varchar(255),
    name          varchar(255),
    last_used_at  timestamp(6) with time zone,
    revoked_at    timestamp(6) with time zone,
    created_at    timestamp(6) with time zone,
    CONSTRAINT api_keys_pkey PRIMARY KEY (id),
    CONSTRAINT api_keys_type_check CHECK (type IN ('SECRET', 'PUBLISHABLE'))
);

-- The authentication hot path: one indexed equality lookup per request.
CREATE UNIQUE INDEX idx_apikey_hash ON api_keys (key_hash);
CREATE INDEX idx_apikey_merchant ON api_keys (merchant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- webhook_endpoints
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_endpoints (
    id                   varchar(255) NOT NULL,
    merchant_id          varchar(255) NOT NULL,
    url                  varchar(2000) NOT NULL,
    -- Per endpoint, not per account: rotating one must not break the others, and a compromised
    -- staging endpoint must not be able to forge events to production.
    secret               varchar(255) NOT NULL,
    description          varchar(255),
    enabled              boolean NOT NULL DEFAULT true,
    -- Comma-separated event types; NULL or blank means "all of them", which is the right default
    -- for a merchant who has not thought about filtering yet.
    event_types          varchar(2000),
    consecutive_failures integer NOT NULL DEFAULT 0,
    disabled_at          timestamp(6) with time zone,
    created_at           timestamp(6) with time zone,
    CONSTRAINT webhook_endpoints_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_endpoint_merchant ON webhook_endpoints (merchant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- webhook_events gains an endpoint reference
--
-- One business event now fans out into one row per subscribed endpoint, each with its own retry
-- state, so a dead endpoint cannot hold up delivery to a healthy one.
-- ---------------------------------------------------------------------------
ALTER TABLE webhook_events ADD COLUMN endpoint_id varchar(255);
CREATE INDEX idx_event_endpoint ON webhook_events (endpoint_id);

-- ---------------------------------------------------------------------------
-- Backfill, then drop the old columns.
--
-- Existing rows are carried across rather than discarded: a database that already has merchants
-- must keep working. The seeded demo keys survive as hashes, so the documented values in the
-- README continue to authenticate.
-- ---------------------------------------------------------------------------

-- Publishable keys: plaintext retained, since they are public by design.
INSERT INTO api_keys (id, merchant_id, type, key_hash, key_prefix, public_value, name, created_at)
SELECT 'key_pk_' || m.id,
       m.id,
       'PUBLISHABLE',
       encode(sha256(m.publishable_key::bytea), 'hex'),
       left(m.publishable_key, 16),
       m.publishable_key,
       'Default publishable key',
       now()
FROM merchants m
WHERE m.publishable_key IS NOT NULL;

-- Secret keys: only the hash survives. There is no way back from here, which is the point.
INSERT INTO api_keys (id, merchant_id, type, key_hash, key_prefix, public_value, name, created_at)
SELECT 'key_sk_' || m.id,
       m.id,
       'SECRET',
       encode(sha256(m.secret_key::bytea), 'hex'),
       left(m.secret_key, 16),
       NULL,
       'Default secret key',
       now()
FROM merchants m
WHERE m.secret_key IS NOT NULL;

-- One endpoint per merchant, from whatever URL they had configured.
INSERT INTO webhook_endpoints (id, merchant_id, url, secret, description, enabled,
                               event_types, consecutive_failures, created_at)
SELECT 'whe_' || m.id,
       m.id,
       m.webhook_url,
       COALESCE(m.webhook_secret, 'whsec_' || md5(random()::text)),
       'Migrated from the account-level webhook URL',
       true,
       NULL,
       0,
       now()
FROM merchants m
WHERE m.webhook_url IS NOT NULL AND m.webhook_url <> '';

-- Point existing undelivered events at the endpoint they would now belong to, so anything queued
-- during the upgrade still gets delivered rather than dead-lettering on a null endpoint.
UPDATE webhook_events e
SET endpoint_id = 'whe_' || e.merchant_id
WHERE e.endpoint_id IS NULL
  AND EXISTS (SELECT 1 FROM webhook_endpoints w WHERE w.id = 'whe_' || e.merchant_id);

ALTER TABLE merchants DROP COLUMN publishable_key;
ALTER TABLE merchants DROP COLUMN secret_key;
ALTER TABLE merchants DROP COLUMN webhook_secret;
ALTER TABLE merchants DROP COLUMN webhook_url;

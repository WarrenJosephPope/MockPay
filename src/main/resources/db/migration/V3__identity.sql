-- V3: people, roles, invitations, audit trail, and server-side sessions.
--
-- Up to now the only caller the gateway understood was an API key belonging to a merchant. This
-- adds the second, entirely separate authentication scheme: humans logging into a dashboard.
--
-- They are different in every respect that matters. An API key lives for months on a server; a
-- session lives for minutes in a browser. A key is revoked by rotating it; a session by logging
-- out. A key is the account; a user is a named person who may hold authority over several accounts.
-- Trying to serve both with one mechanism is how you end up with long-lived credentials in
-- localStorage.

-- ---------------------------------------------------------------------------
-- users
--
-- password_hash is Argon2id — deliberately slow and memory-hard.
--
-- This is the exact opposite of the reasoning behind api_keys.key_hash, and both are correct. An
-- API key is 32 characters from a CSPRNG: nothing to guess, so a fast indexed hash is right. A
-- password is chosen by a human, is short, and is usually reused elsewhere — so if the hash ever
-- leaks, every millisecond of cracking cost is worth paying. Memory-hardness is what defeats the
-- GPU farms that make fast hashes useless here.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                    varchar(255) NOT NULL,
    email                 varchar(255) NOT NULL,
    password_hash         varchar(255) NOT NULL,
    name                  varchar(255),
    email_verified_at     timestamp(6) with time zone,
    mfa_secret            varchar(255),
    last_login_at         timestamp(6) with time zone,
    -- Per-account lockout. Rate limiting by IP does not stop a distributed attempt against one
    -- known address; counting failures per account does.
    failed_login_attempts integer NOT NULL DEFAULT 0,
    locked_until          timestamp(6) with time zone,
    created_at            timestamp(6) with time zone,
    CONSTRAINT users_pkey PRIMARY KEY (id)
);

-- Emails are stored lower-cased, so a plain unique index is enough.
CREATE UNIQUE INDEX uk_users_email ON users (email);

-- ---------------------------------------------------------------------------
-- memberships — which people may act on which accounts, and with what authority
--
-- Role lives here, not on the user, because authority is per account: the same person can own one
-- business and be a read-only observer on another.
-- ---------------------------------------------------------------------------
CREATE TABLE memberships (
    id          varchar(255) NOT NULL,
    user_id     varchar(255) NOT NULL,
    merchant_id varchar(255) NOT NULL,
    role        varchar(255) NOT NULL,
    invited_by  varchar(255),
    created_at  timestamp(6) with time zone,
    CONSTRAINT memberships_pkey PRIMARY KEY (id),
    CONSTRAINT uk_membership UNIQUE (user_id, merchant_id),
    CONSTRAINT memberships_role_check CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'))
);

CREATE INDEX idx_membership_user ON memberships (user_id);
CREATE INDEX idx_membership_merchant ON memberships (merchant_id);

-- ---------------------------------------------------------------------------
-- invitations
--
-- The token is the credential: random, single-use, time-boxed. An invitation link that works
-- forever is a permanent backdoor into someone else's payment account.
-- ---------------------------------------------------------------------------
CREATE TABLE invitations (
    id                 varchar(255) NOT NULL,
    merchant_id        varchar(255) NOT NULL,
    email              varchar(255) NOT NULL,
    role               varchar(255) NOT NULL,
    token              varchar(255) NOT NULL,
    invited_by_user_id varchar(255),
    expires_at         timestamp(6) with time zone,
    accepted_at        timestamp(6) with time zone,
    created_at         timestamp(6) with time zone,
    CONSTRAINT invitations_pkey PRIMARY KEY (id),
    CONSTRAINT invitations_role_check CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'))
);

CREATE UNIQUE INDEX idx_invitation_token ON invitations (token);
CREATE INDEX idx_invitation_merchant ON invitations (merchant_id);

-- ---------------------------------------------------------------------------
-- audit_log — append-only, like the ledger, and for the same reason
--
-- The payment tables record what happened. This records who caused it. An audit log that can be
-- edited is not evidence of anything, so nothing in the codebase updates or deletes these rows.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id          varchar(255) NOT NULL,
    merchant_id varchar(255),
    user_id     varchar(255),
    user_email  varchar(255),
    action      varchar(255) NOT NULL,
    target_type varchar(255),
    target_id   varchar(255),
    detail      varchar(2000),
    ip_address  varchar(255),
    user_agent  varchar(500),
    created_at  timestamp(6) with time zone,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_audit_merchant ON audit_log (merchant_id, created_at DESC);
CREATE INDEX idx_audit_user ON audit_log (user_id);

-- ---------------------------------------------------------------------------
-- Spring Session JDBC
--
-- Sessions live in the database rather than in server memory. Three reasons: they survive a
-- restart, they work unchanged if a second instance is ever added, and an operator can revoke one
-- by deleting a row — which is exactly the property a stateless JWT gives up.
--
-- These tables are Spring Session's, not ours, and the schema is fixed by the library. On the H2
-- dev profile they are created automatically (initialize-schema=embedded); on PostgreSQL Flyway
-- owns them, so they are declared here.
-- ---------------------------------------------------------------------------
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT NOT NULL,
    LAST_ACCESS_TIME      BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME           BIGINT NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

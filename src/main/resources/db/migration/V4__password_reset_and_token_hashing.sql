-- V4: password reset tokens, and hashing of every emailed bearer token.
--
-- Both an invitation token and a password reset token are bearer credentials: whoever holds one can
-- act as somebody. Until now the invitation token was stored in the clear, which meant a database
-- dump handed an attacker the ability to join any account with a pending invitation.
--
-- Same treatment as API keys, and for the same reason: these are long random strings, so a fast
-- indexed hash is both sufficient and correct. Argon2 would buy nothing here (there is nothing to
-- guess) while making lookup impossible to index.

-- ---------------------------------------------------------------------------
-- password_reset_tokens
--
-- Short-lived and single use. A reset link is a full account takeover in one URL, so the window in
-- which a leaked one is dangerous has to be small.
-- ---------------------------------------------------------------------------
CREATE TABLE password_reset_tokens (
    id           varchar(255) NOT NULL,
    user_id      varchar(255) NOT NULL,
    -- SHA-256 of the token that was emailed. The plaintext exists only in the user's inbox.
    token_hash   varchar(255) NOT NULL,
    expires_at   timestamp(6) with time zone NOT NULL,
    used_at      timestamp(6) with time zone,
    -- Recorded so an operator can see where a reset was requested from when a user reports one
    -- they did not ask for.
    requested_ip varchar(255),
    created_at   timestamp(6) with time zone,
    CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_reset_token_hash ON password_reset_tokens (token_hash);
-- Supports invalidating every outstanding token for a user when one is used.
CREATE INDEX idx_reset_token_user ON password_reset_tokens (user_id, used_at);

-- ---------------------------------------------------------------------------
-- Invitation tokens become hashes.
--
-- Existing rows cannot be migrated — the plaintext is gone the moment it is hashed, and we do not
-- have a copy to re-send. Outstanding invitations are therefore invalidated rather than silently
-- broken: they are cheap to reissue, and leaving unusable rows that look valid is worse.
-- ---------------------------------------------------------------------------
UPDATE invitations
SET token = encode(sha256(token::bytea), 'hex')
WHERE accepted_at IS NULL;

-- Any invitation already accepted keeps its row for the audit trail; its token is dead anyway.
UPDATE invitations
SET token = encode(sha256(token::bytea), 'hex')
WHERE accepted_at IS NOT NULL;

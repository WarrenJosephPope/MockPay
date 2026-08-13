# MockPay

A payment gateway that never touches real money, built to be read.

Two things at once:

1. **A mock gateway** you can point a web application at — real HTTP APIs, a browser SDK, a hosted
   checkout, webhooks that actually fire, and every failure mode you need to test against.
2. **A study of how payment gateways work.** The rails are simulated; everything in front of them is
   the real design. Authorisation really is a separate message from capture, the ledger really is
   double-entry, and you can read the ISO 8583 trace for any payment you make.

---

## Run it

Three ways, depending on what you are doing.

### 1. Quickest — in memory, nothing installed

Requires **Java 17+**.

```bash
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

H2 in memory. Everything is wiped when the process exits. Good for the demo and the test suite.

### Seeing the emails

With no `MOCKPAY_SMTP_HOST` set, nothing is sent — each message is written to the log in full,
link included, so the flows work with no mail server at all.

For a real inbox, `docker-compose.dev.yml` includes [Mailpit](https://mailpit.axllent.org/):

```bash
docker compose -f docker-compose.dev.yml up -d
MOCKPAY_SMTP_HOST=localhost MOCKPAY_SMTP_PORT=1025 ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Then read the mail at **<http://localhost:8025>**. For a real provider, set the host, port,
username, password, and turn on `MOCKPAY_SMTP_AUTH` and `MOCKPAY_SMTP_STARTTLS`.

### 2. Development — Postgres in Docker, app from the CLI

Requires **Java 17+** and **Docker**. This is the day-to-day loop: fast rebuilds, a debugger, live
logs, and data that survives a restart.

```bash
docker compose -f docker-compose.dev.yml up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

### 3. Everything in Docker

Requires **Docker** only. Nothing else on your machine.

```bash
docker compose up -d --build
docker compose logs -f gateway
```

Whichever you pick, open **<http://localhost:8088/>** for the demo checkout.

| | |
|---|---|
| Demo checkout | <http://localhost:8088/> |
| Test instruments | <http://localhost:8088/v1/public/test_instruments> |
| Webhook sink | <http://localhost:8088/webhook-sink/received> |
| H2 console | <http://localhost:8088/h2-console> — option 1 only (JDBC `jdbc:h2:mem:mockpay`, user `sa`, no password) |
| Postgres | `localhost:5433`, db/user/password all `mockpay` — options 2 and 3 |
| Mailpit inbox | <http://localhost:8025> — only when SMTP is pointed at it |

### Stopping and wiping

```bash
docker compose down                              # stop, keep data
docker compose down && rm -rf volumes/postgres   # stop and start clean
```

Postgres data lives in `./volumes/postgres` — a bind mount rather than a named Docker volume, so you
can see it, back it up, and delete it with `rm -rf`. It is gitignored. Both compose files mount the
same directory, so you can switch between options 2 and 3 without losing anything.

---

## Accounts

Two are seeded at startup with fixed keys:

| Account | Secret key | Publishable key | Settles |
|---|---|---|---|
| Demo Store (US) | `sk_test_demo_us_secret` | `pk_test_demo_us_publishable` | USD |
| Demo Store (India) | `sk_test_demo_in_secret` | `pk_test_demo_in_publishable` | INR |

On Postgres they are created once and persist. On H2 they are recreated every start. Disable them
entirely with `MOCKPAY_SEED_DEMO_ACCOUNTS=false` — publicly documented credentials are a backdoor,
not a convenience.

**Secret keys** (`sk_`) are server-side only and can move money. **Publishable keys** (`pk_`) are
safe in a browser and can only tokenise. That split is what makes a client-side payment form safe.

Secret keys are stored as **SHA-256 hashes**. Even the seeded ones: `sk_test_demo_us_secret` works
because its *hash* is what was written. A new key's value is returned exactly once, by the call that
creates it, and cannot be recovered afterwards.

### Signing up

There is a dashboard API with real accounts, sessions and roles:

```bash
# Register a person and the business they own; returns the API keys once
curl -s -c jar.txt -X POST http://localhost:8088/dashboard/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"correct horse battery staple",
       "name":"You","business_name":"Acme Ltd","currency":"GBP","country":"GB"}'

curl -s -b jar.txt http://localhost:8088/dashboard/me
```

Mutating dashboard calls need a CSRF token — read the `XSRF-TOKEN` cookie and echo it back in the
`X-XSRF-TOKEN` header. `scripts/smoke-test.sh` has a four-line implementation.

**Roles.** OWNER manages the team · ADMIN issues keys and refunds · DEVELOPER configures
integrations but **cannot move money** · VIEWER is read-only. Every mutation is written to an audit
log with the actor, their IP, and their user agent.

### Forgotten passwords and invitations

Both are emailed. `POST /dashboard/auth/forgot-password` always returns the same response whether or
not the address is registered — otherwise it becomes a way to discover who has an account.

Completing a reset **signs the user out everywhere**. That is the point of resetting after a scare:
anyone holding a stolen session loses it immediately. A stateless token could not do that.

Reset links live one hour, invitation links seven days, and both are single-use. Only their SHA-256
hashes are stored, so a database leak yields no working links.

### Creating an account without a browser

With seeding off, an empty database has no accounts — and you cannot authenticate to create one.
The bootstrap command breaks that circle:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres \
  -Dspring-boot.run.arguments="--bootstrap.name=Acme --bootstrap.country=GB --bootstrap.currency=GBP"

# or, in Docker
docker compose run --rm gateway --bootstrap.name=Acme --bootstrap.country=GB
```

It creates the account, prints the key pair once, and exits.

---

## Take a payment in 60 seconds

```bash
SK=sk_test_demo_us_secret

# 1. Tokenise a card (in production this happens in the browser, not here)
PM=$(curl -s -X POST http://localhost:8088/v1/payment_methods \
  -H "Authorization: Bearer $SK" -H 'Content-Type: application/json' \
  -d '{"type":"card","card":{"number":"4242424242424242","exp_month":12,"exp_year":2030,"cvc":"123"}}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['id'])")

# 2. Create and confirm in one call
curl -s -X POST http://localhost:8088/v1/payment_intents \
  -H "Authorization: Bearer $SK" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"amount\":4999,\"currency\":\"USD\",\"payment_method\":\"$PM\",\"confirm\":true}"
```

Amounts are integers in the currency's **minor unit** — `4999` is $49.99. `JPY` has no minor unit, so
`4999` is ¥4999.

### Then read what happened

```bash
PI=pi_...   # from the response above

# The actual ISO 8583 messages that went to the "network"
curl -s "http://localhost:8088/v1/payment_intents/$PI/transactions" -H "Authorization: Bearer $SK"

# The double-entry journal this payment produced
curl -s "http://localhost:8088/v1/payment_intents/$PI/ledger" -H "Authorization: Bearer $SK"

# Trial balance — _TOTAL_MUST_BE_ZERO is an assertion, not decoration
curl -s "http://localhost:8088/v1/account/balance" -H "Authorization: Bearer $SK"
```

The transaction trace is the most instructive endpoint in the project:

```
MTI  0100   (ISO 8583:1987 / Authorization / Request / from Acquirer)
BMP1 F234440108E18000
DE2    Primary Account Number (PAN)       424242******4242
DE3    Processing Code                    000000
DE4    Amount, Transaction                000000004999
DE11   System Trace Audit Number (STAN)   184857
DE22   Point of Service Entry Mode        81
DE37   Retrieval Reference Number (RRN)   122555204325
DE39   Response Code                      00
DE49   Currency Code, Transaction         840
DE126  Private Use - 3DS/CAVV             CAVV=Z2NKVUE3dHdpVHVSYlFOMG8ySmg=;ECI=05;3DSVER=2.2.0
```

---

## From a browser

Include the SDK and use a publishable key. The card number goes from the customer's keyboard
straight to the gateway — your backend never sees it, which is the point.

```html
<script src="http://localhost:8088/mockpay.js"></script>
<script>
const mockpay = MockPay('pk_test_demo_us_publishable');

// 1. Tokenise
const { paymentMethod, error } = await mockpay.createPaymentMethod({
  type: 'card',
  card: { number: '4242424242424242', exp_month: 12, exp_year: 2030, cvc: '123' }
});

// 2. Confirm. clientSecret comes from a PaymentIntent your server created.
//    The SDK handles 3DS challenges and redirects, and waits for the outcome.
const result = await mockpay.confirmPayment({
  clientSecret,
  paymentMethod: paymentMethod.id,
  onAction: (a) => console.log('customer must do:', a.type)
});

if (result.error) showDeclineMessage(result.error.message);
else fulfilOrder(result.paymentIntent);
</script>
```

`confirmPayment` resolves only when the payment reaches a terminal state — including after a 3-D
Secure challenge or a wallet redirect. It reads the outcome from the server rather than inferring it
from the popup closing, because a customer who pays and then closes the window has still paid.

---

## API reference

Base URL `http://localhost:8088`. Auth is `Authorization: Bearer sk_...`.

### PaymentIntents

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/payment_intents` | `amount`, `currency` required. Optional `capture_method` (`automatic`\|`manual`), `payment_method`, `confirm`, `description`, `customer`, `statement_descriptor`, `metadata` |
| `GET` | `/v1/payment_intents/{id}` | |
| `GET` | `/v1/payment_intents?page=0&limit=20&status=succeeded` | |
| `POST` | `/v1/payment_intents/{id}/confirm` | `{ "payment_method": "pm_...", "return_url": "..." }` |
| `POST` | `/v1/payment_intents/{id}/capture` | `{ "amount_to_capture": 5000 }`, or omit for full |
| `POST` | `/v1/payment_intents/{id}/cancel` | Reverses the authorisation |
| `GET` | `/v1/payment_intents/{id}/transactions` | The ISO 8583 trace |
| `GET` | `/v1/payment_intents/{id}/ledger` | Double-entry journals |
| `GET` | `/v1/payment_intents/{id}/refunds` | |

**Statuses:** `requires_payment_method` → `requires_confirmation` → `requires_action` →
`processing` → `requires_capture` → `succeeded`, plus `canceled` and `failed`.

`failed` is **not** terminal — attach a different card and confirm again, keeping one id per order.
And you must handle `requires_action`: if you only handle `succeeded` and `failed` you will silently
lose every payment that needed a 3-D Secure challenge.

### Everything else

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/payment_methods` | `{"type":"card"\|"upi"\|"wallet", ...}` |
| `POST` | `/v1/refunds` | `{"payment_intent":"pi_...","amount":2000,"reason":"..."}` |
| `GET` | `/v1/refunds/{id}` | |
| `POST` | `/v1/disputes` | Test affordance — opens a chargeback |
| `POST` | `/v1/disputes/{id}/evidence` | Before `evidence_due_by` |
| `POST` | `/v1/disputes/{id}/resolve` | `{"merchant_wins":true}` |
| `GET` | `/v1/dispute_reason_codes` | Visa codes with categories |
| `POST` | `/v1/settlements/run` | Closes a period, computes the net position |
| `POST` | `/v1/settlements/{id}/payout` | |
| `GET` | `/v1/events` | The webhook outbox: attempts, backoff, errors |
| `POST` | `/v1/events/{id}/replay` | |
| `GET` | `/v1/account` · `GET /v1/account/balance` | |

### Dashboard (session cookie, not API key)

| Method | Path | Minimum role |
|---|---|---|
| `POST` | `/dashboard/auth/signup` · `/login` · `/logout` · `/accept-invitation` | — |
| `POST` | `/dashboard/auth/forgot-password` · `/reset-password` | — |
| `GET` | `/dashboard/me` · `POST /dashboard/switch-account` | any |
| `GET` | `/dashboard/payments` · `/dashboard/payments/{id}` | VIEWER |
| `GET` | `/dashboard/team` | VIEWER |
| `GET`/`POST`/`PATCH`/`DELETE` | `/dashboard/webhook-endpoints` | DEVELOPER |
| `GET` | `/dashboard/api-keys` · `/dashboard/events` · `POST /dashboard/events/{id}/replay` | DEVELOPER |
| `POST` | `/dashboard/api-keys` · `/dashboard/api-keys/{id}/revoke` | ADMIN |
| `POST` | `/dashboard/refunds` | ADMIN |
| `GET` | `/dashboard/audit-log` | ADMIN |
| `POST`/`PATCH`/`DELETE` | `/dashboard/team/**` | OWNER |

### API keys

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/api_keys` | Prefixes and usage only — secret values are never returned |
| `POST` | `/v1/api_keys` | `{"type":"secret","name":"CI"}`. **The response is the only time the key is readable.** |
| `POST` | `/v1/api_keys/{id}/revoke` | Refuses if it is the account's last active secret key |

Rotation without downtime: create the replacement, deploy it, watch `last_used_at` on the old key
stop moving, then revoke it.

### Webhook endpoints

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/webhook_endpoints` | |
| `POST` | `/v1/webhook_endpoints` | `{"url":"...","enabled_events":["payment_intent.succeeded"]}` — omit `enabled_events` for all |
| `GET` | `/v1/webhook_endpoints/{id}` | |
| `PATCH` | `/v1/webhook_endpoints/{id}` | Change the URL, the filter, or `enabled` |
| `DELETE` | `/v1/webhook_endpoints/{id}` | |
| `POST` | `/v1/account/webhook` | Shortcut: replace all endpoints with one URL |

Each endpoint has **its own signing secret**, so rotating one does not break the others and a
compromised staging endpoint cannot forge events to production. One business event fans out into one
delivery per subscribed endpoint, each with independent retry state.

### Errors

```json
{ "error": { "type": "card_error", "code": "card_declined",
             "decline_code": "insufficient_funds", "message": "Insufficient funds" } }
```

Branch on `code` and `decline_code`, never on `message` — messages are prose and will change.

### Idempotency

Send `Idempotency-Key: <uuid>` on any mutating request.

- Same key, same body → the original response, with `Idempotent-Replayed: true`
- Same key, **different** body → `422 idempotency_key_reused` (a real client bug, caught loudly)
- Same key while the first is in flight → `409 idempotency_in_progress`
- Keys expire after 24 hours

---

## Test instruments

Deterministic, so you can write assertions. Full list at `/v1/public/test_instruments`.

### Cards

| Number | Result |
|---|---|
| `4242424242424242` | Approves (Visa credit) |
| `4000056655665556` | Approves (Visa **debit** — different interchange) |
| `5555555555554444` | Approves (Mastercard) |
| `6521000000000008` | Approves (**RuPay** — clears via NPCI, not Visa/MC) |
| `4000000000000002` | Decline — `card_declined` (DE39 `05`) |
| `4000000000009995` | Decline — `insufficient_funds` (`51`) · **soft**, retryable |
| `4000000000000069` | Decline — `expired_card` (`54`) · hard |
| `4000000000000127` | Decline — `incorrect_cvc` (`82`) · hard |
| `4000000000000259` | Decline — `lost_card` (`41`) · hard, never retry |
| `4000000000000119` | Decline — `issuer_unavailable` (`91`) · **soft**, auto-retries on a 2nd acquirer |
| `4000002500003155` | **3-D Secure challenge**, then approves. OTP `123456` |
| `4000008400001629` | 3-D Secure challenge that the cardholder fails |
| `4100000000000019` | Blocked by the risk engine **before** the network sees it |

Any other Luhn-valid number approves.

### UPI — use the India account

`success@mockpay` · `failure@mockpay` · `insufficient@mockpay` · `pending@mockpay` (expires) ·
`risk@mockpay`

### Wallets

`mockwallet` approves · `failwallet` cancels

---

## Webhooks

These are real HTTP POSTs, not stubs. Point them at your own site:

```bash
# Simple: one endpoint, all events
curl -X POST http://localhost:8088/v1/account/webhook \
  -H "Authorization: Bearer $SK" -H 'Content-Type: application/json' \
  -d '{"url":"http://localhost:3000/api/webhooks/mockpay"}'

# Or several endpoints, each with its own filter and its own secret
curl -X POST http://localhost:8088/v1/webhook_endpoints \
  -H "Authorization: Bearer $SK" -H 'Content-Type: application/json' \
  -d '{"url":"http://localhost:3000/webhooks/orders",
       "enabled_events":["payment_intent.succeeded","payment_intent.payment_failed"]}'
```

Events: `payment_intent.created` · `.requires_action` · `.authorized` · `.succeeded` ·
`.payment_failed` · `.canceled` · `.refunded` · `.partially_refunded` · `refund.succeeded` ·
`refund.failed` · `dispute.created` · `.updated` · `.won` · `.lost` · `.closed` ·
`settlement.created` · `payout.paid`

### Verifying a signature

```
MockPay-Signature: t=1786531234,v1=5257a869e7...
```

HMAC-SHA256 over `"{timestamp}.{raw_body}"` using **that endpoint's** secret, returned by
`GET /v1/webhook_endpoints`.

Three things your handler must do — `api/WebhookSinkController.java` is a working example:

1. **Verify over the raw bytes.** Parsing JSON and re-serialising reorders keys and changes
   whitespace; the signature is over bytes. This is the most common integration failure.
2. **Reject stale timestamps** (5 minutes). A valid signature is otherwise replayable forever.
3. **Deduplicate on `MockPay-Event-Id`.** Delivery is at-least-once by design — exactly-once
   delivery is impossible over a network; exactly-once *processing* is your side of the contract.

Return `2xx` to acknowledge. Anything else retries at 5s, 30s, 2m, 10m, 1h, 6h, then dead-letters.
Replay with `POST /v1/events/{id}/replay`.

> **If your site runs on `localhost`,** note that production gateways block webhook URLs resolving to
> private IP ranges as an SSRF defence. This one deliberately does not, because blocking them would
> make local development impossible.

---

## What is modelled

| | Where |
|---|---|
| PaymentIntent state machine, incl. `requires_action` | `domain/PaymentIntent.java` |
| Tokenisation, network tokens, PAN never stored | `service/TokenizationService.java` |
| ISO 8583 with real bitmaps (0100/0110/0220/0400) | `rails/Iso8583Message.java`, `rails/CardNetworkSimulator.java` |
| EMV 3-D Secure 2.2 — frictionless and challenge | `rails/ThreeDsSimulator.java` |
| UPI collect flow, NPCI-style error codes | `rails/UpiSimulator.java` |
| Wallet redirect, including the abandoned-tab case | `rails/WalletSimulator.java` |
| Risk scoring — allow / challenge / block | `rails/RiskEngine.java` |
| Smart routing, local acquiring, soft-decline retry | `rails/AcquirerRouter.java` |
| Double-entry ledger with enforced zero-sum | `service/LedgerService.java` |
| Idempotency keys with fingerprinting | `service/IdempotencyService.java` |
| Webhook outbox, HMAC signing, backoff, dead-letter | `service/EventService.java` |
| Webhook **receiver** done correctly | `api/WebhookSinkController.java` |
| Chargeback lifecycle with Visa reason codes | `service/DisputeService.java` |
| Net settlement, T+2 business days, payouts | `service/SettlementService.java` |
| Key separation, rate limiting, tenant isolation | `api/ApiKeyFilter.java` |
| Three coexisting auth schemes, CSRF where it matters | `config/SecurityConfig.java` |
| Argon2 passwords, lockout, uniform login errors | `service/UserService.java` |
| Roles and per-endpoint authority | `domain/Membership.java`, `api/DashboardController.java` |
| Append-only audit trail | `service/AuditService.java` |
| Password reset, hashed single-use tokens | `service/UserService.java`, `domain/PasswordResetToken.java` |
| Email with a console fallback | `service/EmailService.java` |
| Sign out everywhere | `service/SessionRegistry.java` |
| Hashed API keys, rotation, revocation | `service/ApiKeyService.java` |
| Multi-endpoint webhooks with event filtering | `domain/WebhookEndpoint.java`, `service/EventService.java` |
| Account creation and bootstrap | `service/AccountService.java`, `config/BootstrapRunner.java` |

Every class carries a Javadoc comment explaining *why* it works that way, not just what it does.
Hover over a type in your IDE to read it.

---

## Testing

186 end-to-end assertions across every flow:

```bash
bash scripts/smoke-test.sh
```

It expects a running instance on port 8088 and passes identically on H2 and Postgres.

---

## Configuration

**Every configurable value comes from an environment variable**, including the ports. Nothing
requires editing a file, and every variable has a default — so the project runs with no
configuration at all.

```bash
cp .env.example .env      # then edit whatever you need
```

Docker Compose reads `.env` from this directory automatically and injects the values into both
containers. Running from the CLI, export them or let the defaults apply. `.env` is gitignored;
`.env.example` is tracked and is the reference for every available setting.

### The ones you are most likely to change

| Variable | Default | Effect |
|---|---|---|
| `GATEWAY_PORT` | `8088` | Port the gateway listens on, inside the container and on the host |
| `POSTGRES_PORT` | `5433` | Host port for Postgres (5432 inside the network, not configurable) |
| `POSTGRES_DB` / `_USER` / `_PASSWORD` | `mockpay` | Database credentials |
| `POSTGRES_DATA_DIR` | `./volumes/postgres` | Where the database files live |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` = H2 in memory · `postgres` = PostgreSQL + Flyway |
| `MOCKPAY_SEED_DEMO_ACCOUNTS` | `true` | Creates the two demo accounts. **Set false anywhere real.** |
| `MOCKPAY_PUBLIC_BASE_URL` | `http://localhost:${GATEWAY_PORT}` | Goes into 3DS/UPI/wallet redirects |
| `MOCKPAY_RAIL_MAX_LATENCY_MS` | `400` | Raise it to exercise your own timeout handling |
| `MOCKPAY_PRICING_CARD_BPS` | `200` | 200 bps = 2.00% |
| `MOCKPAY_SETTLEMENT_DELAY_DAYS` | `2` | T+N **business** days from capture to payout |
| `MOCKPAY_SETTLEMENT_ZONE` | `UTC` | Which timezone the settlement *day* is measured in |
| `MOCKPAY_COOKIE_SECURE` | `false` | Set true behind TLS; the browser drops secure cookies on plain HTTP |
| `MOCKPAY_SESSION_TIMEOUT` | `8h` | Dashboard session lifetime |
| `MOCKPAY_SMTP_HOST` | *(empty)* | **Empty means emails are written to the log instead of sent** |
| `MOCKPAY_SMTP_PORT` / `_USERNAME` / `_PASSWORD` | `1025` / — / — | SMTP credentials |
| `MOCKPAY_SMTP_AUTH` / `_STARTTLS` | `false` | Turn both on for a real provider |
| `MOCKPAY_MAIL_FROM` | `MockPay <no-reply@mockpay.local>` | Must match an authenticated domain |
| `MOCKPAY_WEBHOOK_BACKOFF_SECONDS` | `5,30,120,600,3600,21600` | Retry schedule, one entry per attempt |

`GATEWAY_PORT` is the one worth understanding: change it and the published port, the container's own
healthcheck, the redirect URLs the gateway hands to customers, the seeded webhook endpoint, and the
smoke test's target all follow. Nothing else needs touching.

```bash
GATEWAY_PORT=9000 docker compose up -d --build
bash scripts/smoke-test.sh          # picks up .env, targets :9000 by itself
```

Database credentials are read from the environment specifically so nothing real ever has to live in
a committed file.

### Files

| File | Purpose |
|---|---|
| `.env.example` | Every variable, documented, with its default |
| `application.yml` | Shared settings, each one `${VAR:default}` |
| `application-dev.yml` | H2 in memory, `ddl-auto: create-drop`, Flyway off |
| `application-postgres.yml` | PostgreSQL, Flyway on, `ddl-auto: validate` |
| `db/migration/` | Versioned schema. **Never edit an applied migration** — Flyway checksums them |

On Postgres, Hibernate runs with `ddl-auto: validate` — it compares the entities against the schema
Flyway built and **refuses to start** if they disagree. A forgotten migration is a loud startup
failure rather than a missing column discovered in production.

---

## What this is not

- **Not PCI compliant, and not trying to be.** It accepts card numbers over plain HTTP on localhost.
  Never send it a real card number.
- **Not a production gateway.** No clustering, no HSM, no real rails, no merchant signup.
- **Not exhaustive.** Instalments, multi-party payouts, FX, and subscriptions are out of scope.

The simulated rails are the honest part: they reproduce the *shape* and *semantics* of card, UPI, and
wallet flows, not the wire protocol. You could not point this at Visa. That is deliberate — the
interesting engineering is everything in front of the rail, and that part is real.

---

## Layout

```
gateway-service/
├── .env.example                every setting, documented
├── docker-compose.yml          Postgres + the gateway
├── docker-compose.dev.yml      Postgres only; run the app from the CLI
├── Dockerfile                  multi-stage build, non-root runtime
├── scripts/smoke-test.sh       186 end-to-end assertions
├── volumes/postgres/           bind-mounted database files (gitignored)
└── src/main/
    ├── java/dev/mockpay/gateway/
    │   ├── api/        controllers, auth filter, error handling
    │   ├── domain/     JPA entities and the state machine
    │   ├── rails/      the simulated networks
    │   ├── repo/       tenant-scoped repositories
    │   ├── service/    orchestration, ledger, webhooks, idempotency
    │   └── support/    money, ids, crypto
    └── resources/
        ├── application*.yml    config, one file per profile
        ├── db/migration/       versioned schema
        └── static/
            ├── index.html      demo checkout
            ├── mockpay.js      browser SDK
            └── challenge/      simulated issuer ACS, UPI app, wallet
```

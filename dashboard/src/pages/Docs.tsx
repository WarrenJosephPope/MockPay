import { useMemo, useState, type ReactNode } from 'react';
import { api, atLeast, type ApiKey, type Listed, type Role, type WebhookEndpoint } from '../api';
import { Banner, Spinner, useLoad } from '../ui';

/**
 * Integration guide, rendered with this account's own credentials substituted in.
 *
 * The reason this exists inside the dashboard rather than as a static page: every snippet in a
 * generic guide has a placeholder in it, and a placeholder is a step where the reader can get it
 * wrong. Here the publishable key, the base URL and the signing secret are the real ones, so the
 * first snippet a developer copies works against their own account on the first attempt.
 *
 * Nothing secret is revealed that the page did not already have the right to show — publishable
 * keys are public by design, webhook secrets are already listed on the endpoints page, and secret
 * keys are never rendered here at all, only their prefix.
 */

type Instruments = {
  cards: { number: string; brand: string; funding: string; behaviour: string; note: string }[];
  upi: { vpa: string; behaviour: string }[];
  wallets: { provider: string; behaviour: string }[];
  three_ds_otp: string;
};

const SECTIONS = [
  ['flow', 'How a payment flows'],
  ['keys', 'Your credentials'],
  ['step1', '1. Create a PaymentIntent'],
  ['step2', '2. Open checkout'],
  ['step3', '3. Confirm on your server'],
  ['testing', 'Simulating outcomes'],
  ['idempotency', 'Idempotency'],
  ['errors', 'Errors'],
  ['reference', 'Endpoint reference'],
  ['mistakes', 'Three ways to get this wrong'],
] as const;

export default function Docs({ role }: { role: Role }) {
  const canSeeKeys = atLeast(role, 'DEVELOPER');

  const keys = useLoad<Listed<ApiKey>>(
    () => (canSeeKeys ? api.get('/dashboard/api-keys') : Promise.resolve({ data: [] })),
  );
  const endpoints = useLoad<Listed<WebhookEndpoint>>(
    () => (canSeeKeys ? api.get('/dashboard/webhook-endpoints') : Promise.resolve({ data: [] })),
  );
  const instruments = useLoad<Instruments>(() =>
    fetch('/v1/public/test_instruments').then((r) => r.json()),
  );

  const base = window.location.origin;

  // The newest live key of each type. Revoked keys are excluded — pasting one into a guide would
  // send the reader off to debug a 401 that the page itself caused.
  const live = (k: ApiKey) => !k.revoked_at;
  const publishable =
    keys.data?.data.find((k) => k.type === 'publishable' && live(k))?.key ?? 'pk_test_…';
  const secretPrefix =
    keys.data?.data.find((k) => k.type === 'secret' && live(k))?.prefix ?? 'sk_test_…';
  const webhookSecret = endpoints.data?.data.find((e) => e.enabled)?.secret ?? 'whsec_…';

  const hasSecret = secretPrefix !== 'sk_test_…';
  const hasEndpoint = webhookSecret !== 'whsec_…';

  const snippets = useMemo(
    () => build({ base, publishable, secretPrefix, webhookSecret }),
    [base, publishable, secretPrefix, webhookSecret],
  );

  return (
    <>
      <h1>Docs</h1>
      <div className="sub">
        Everything needed to take a payment, with this account's keys already filled in. Three
        server calls and one script tag.
      </div>

      <nav className="toc">
        {SECTIONS.map(([id, label]) => (
          <a key={id} href={`#${id}`}>{label}</a>
        ))}
      </nav>

      {/* ------------------------------------------------------------------ flow */}
      <Section id="flow" title="How a payment flows">
        <p className="muted">
          The browser never holds a secret and never decides an outcome. Your server creates the
          payment and your server confirms it; the middle step only collects the card details, on
          our origin, so they never touch your page.
        </p>
        <pre className="trace">{FLOW}</pre>
        <p className="muted">
          Steps 1 and 5 are the ones that matter. If you only take one thing from this page: the
          browser telling you it succeeded is a hint, and step 5 is the proof.
        </p>
      </Section>

      {/* ------------------------------------------------------------------ keys */}
      <Section id="keys" title="Your credentials">
        {!canSeeKeys && (
          <Banner kind="info">
            Keys are hidden for your role, so the snippets below use placeholders. Ask an admin for a
            developer seat to see the real values.
          </Banner>
        )}
        {keys.loading && <Spinner />}
        {keys.error && <Banner kind="bad">{keys.error}</Banner>}

        <dl className="kv">
          <dt>Base URL</dt>
          <dd><code>{base}</code></dd>
          <dt>Publishable key</dt>
          <dd>
            <code>{publishable}</code>
            <div className="muted small">
              Goes in your web page. Can only tokenise a card and read an intent it already has the
              client secret for.
            </div>
          </dd>
          <dt>Secret key</dt>
          <dd>
            <code>{secretPrefix}…</code>
            <div className="muted small">
              Server only. Full authority over this account — never ship it to a browser, a mobile
              app, or a public repository.{' '}
              {!hasSecret && <a href="/api-keys">Create one →</a>}
            </div>
          </dd>
          <dt>Authentication</dt>
          <dd>
            HTTP Basic, secret key as the username and an empty password:{' '}
            <code>-u {secretPrefix}…:</code> — note the trailing colon.
          </dd>
        </dl>
      </Section>

      {/* ------------------------------------------------------------------ step 1 */}
      <Section id="step1" title="1. Create a PaymentIntent on your server">
        <p className="muted">
          Do this when the customer reaches checkout. The response contains a{' '}
          <code>client_secret</code>, which is the only thing you pass to the browser — it scopes
          the page to this one payment and nothing else on your account.
        </p>
        <Snippet tabs={snippets.create} />
        <p className="muted">
          <code>amount</code> is in <strong>minor units</strong> — 4999 is $49.99. Never send a
          float; representing money as a binary fraction is how rounding errors get into ledgers.
        </p>
      </Section>

      {/* ------------------------------------------------------------------ step 2 */}
      <Section id="step2" title="2. Open checkout in the browser">
        <p className="muted">
          One script tag and one call. The payment sheet is our page in an iframe over yours, so you
          write no card form and the card number never enters your document — which is the
          difference between PCI SAQ A-EP and SAQ A.
        </p>
        <Snippet tabs={snippets.open} />
        <p className="muted">
          Embedding from another origin? The sheet sends{' '}
          <code>Content-Security-Policy: frame-ancestors</code>, defaulting to <code>'self'</code>.
          Add your domain with <code>MOCKPAY_FRAME_ANCESTORS</code> or the frame will refuse to
          load.
        </p>
      </Section>

      {/* ------------------------------------------------------------------ step 3 */}
      <Section id="step3" title="3. Confirm on your server">
        <p className="muted">
          Fulfil from the webhook, not from <code>onSuccess</code>. A customer controls their
          browser completely — they can close it, replay the callback, or fake it. They do not
          control your webhook endpoint.
        </p>
        {!hasEndpoint && canSeeKeys && (
          <Banner kind="info">
            No webhook endpoint yet, so the secret below is a placeholder.{' '}
            <a href="/endpoints">Add one →</a>
          </Banner>
        )}
        <Snippet tabs={snippets.webhook} />
        <p className="muted">
          Three rules, each of which is a real vulnerability if broken. Verify against the{' '}
          <strong>raw request bytes</strong> — re-serialising the JSON changes them and the HMAC
          will not match. Reject timestamps older than five minutes, or a captured delivery can be
          replayed forever. Deduplicate on the event id, because delivery is at-least-once by
          design.
        </p>
        <p className="muted">
          Events are also queryable, so a webhook you missed is never lost: see the{' '}
          <a href="/events">event log</a>, which can replay any delivery.
        </p>
      </Section>

      {/* ------------------------------------------------------------------ testing */}
      <Section id="testing" title="Simulating outcomes">
        <p className="muted">
          There is no test-mode switch — the instrument you use <em>is</em> the switch. Type these
          into the payment sheet.
        </p>

        {instruments.loading && <Spinner />}
        {instruments.error && <Banner kind="bad">{instruments.error}</Banner>}

        {instruments.data && (
          <>
            <h3>Cards</h3>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>Number</th><th>Brand</th><th>What happens</th></tr>
                </thead>
                <tbody>
                  {instruments.data.cards.map((c) => (
                    <tr key={c.number}>
                      <td><code>{c.number}</code></td>
                      <td className="muted">{c.brand} {c.funding}</td>
                      <td>{c.note}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="muted">
              Any other Luhn-valid number approves. The 3-D Secure OTP is{' '}
              <code>{instruments.data.three_ds_otp}</code>.
            </p>

            <h3>UPI</h3>
            <p className="muted">
              Requires an INR account. A collect request returns <code>pending</code> rather than a
              verdict — approval happens in the payer's app, which is why the status is
              <code> requires_action</code> and the outcome arrives later.
            </p>
            <div className="row">
              {instruments.data.upi.map((u) => (
                <span key={u.vpa} className="pill muted">
                  {u.vpa} — {u.behaviour.toLowerCase().replace(/_/g, ' ')}
                </span>
              ))}
            </div>

            <h3>Wallets</h3>
            <div className="row">
              {instruments.data.wallets.map((w) => (
                <span key={w.provider} className="pill muted">
                  {w.provider} — {w.behaviour.toLowerCase().replace(/_/g, ' ')}
                </span>
              ))}
            </div>
          </>
        )}

        <h3>Abandonment, which is the case worth testing hardest</h3>
        <p className="muted">
          Start a payment that needs an action — a 3-D Secure card or a UPI collect — and then do
          nothing. After the action expires (5 minutes for UPI, 15 for 3DS and wallets) a sweeper
          fails the intent with <code>action_expired</code> and sends{' '}
          <code>payment_intent.payment_failed</code>. This is the flow that breaks real
          integrations: the customer closed their laptop, and the outcome arrives long after your
          page gave up.
        </p>
      </Section>

      {/* ------------------------------------------------------------------ idempotency */}
      <Section id="idempotency" title="Idempotency">
        <p className="muted">
          Send an <code>Idempotency-Key</code> header on every POST. A timeout tells you nothing
          about whether the request was processed, and the retry you are about to send is how
          customers get charged twice.
        </p>
        <Snippet tabs={snippets.idempotency} />
        <p className="muted">
          Generate the key <strong>per logical operation</strong>, not per HTTP attempt — a fresh
          UUID when the customer clicks Pay, reused by every retry of that click. Replays return the
          original response with <code>Idempotent-Replayed: true</code>. Reusing a key with a
          different body is rejected, so a key can never be made to mean two things.
        </p>
      </Section>

      {/* ------------------------------------------------------------------ errors */}
      <Section id="errors" title="Errors">
        <p className="muted">
          Every failure has the same shape, so you can write one handler.
        </p>
        <Snippet tabs={snippets.errors} />
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>type</th><th>Means</th><th>Do</th></tr>
            </thead>
            <tbody>
              <tr>
                <td><code>card_error</code></td>
                <td>The issuer declined</td>
                <td>Show <code>message</code> to the customer; branch on <code>decline_code</code></td>
              </tr>
              <tr>
                <td><code>invalid_request_error</code></td>
                <td>Your request was wrong</td>
                <td>Fix the call — retrying will not help</td>
              </tr>
              <tr>
                <td><code>authentication_error</code></td>
                <td>Bad or revoked key</td>
                <td>Check which key the environment loaded</td>
              </tr>
              <tr>
                <td><code>rate_limit_error</code></td>
                <td>Too many requests</td>
                <td>Back off exponentially and retry with the same idempotency key</td>
              </tr>
              <tr>
                <td><code>api_error</code></td>
                <td>Our fault</td>
                <td>Retry with the same idempotency key</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="muted">
          A decline is a <strong>successful API call</strong> that returns HTTP 402 — the system
          worked, the issuer said no. Distinguish it from a 500, which means nobody knows what
          happened and the payment may or may not exist. Soft declines like{' '}
          <code>insufficient_funds</code> are worth retrying later; hard ones like{' '}
          <code>lost_card</code> never are.
        </p>
      </Section>

      {/* ------------------------------------------------------------------ reference */}
      <Section id="reference" title="Endpoint reference">
        <p className="muted">
          Secret key on everything under <code>/v1</code> except <code>/v1/public</code>, which the
          browser calls with a publishable key and a client secret.
        </p>
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Endpoint</th><th>Does</th></tr>
            </thead>
            <tbody>
              {ENDPOINTS.map(([call, what]) => (
                <tr key={call}>
                  <td><code>{call}</code></td>
                  <td>{what}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* ------------------------------------------------------------------ mistakes */}
      <Section id="mistakes" title="Three ways to get this wrong">
        <ol className="muted spaced">
          <li>
            <strong>Fulfilling on the browser callback.</strong> <code>onSuccess</code> is a UI
            signal. Ship goods on the webhook, or on a server-side fetch of the intent with your
            secret key.
          </li>
          <li>
            <strong>Trusting the amount from the client.</strong> Compute it on your server from
            your own prices. If the browser sends the amount, the browser sets the price.
          </li>
          <li>
            <strong>Skipping the idempotency key</strong> because it works in testing. It always
            works until the one timeout that double-charges someone, and that is the first time
            anyone notices.
          </li>
        </ol>
        <p className="muted">
          Worth knowing: closing the sheet mid-challenge fires <code>onClose</code>, but the payment
          keeps going server-side. The customer can still approve it, and you will get a{' '}
          <code>succeeded</code> webhook for a payment your page treated as abandoned. Real gateways
          behave the same way, and it is the other half of why fulfilment belongs on the webhook.
        </p>
        <p className="muted">
          Both integration modes are running at{' '}
          <a href="/checkout" target="_blank" rel="noreferrer">the demo checkout ↗</a>.
        </p>
      </Section>
    </>
  );
}

// ---------------------------------------------------------------------------
// Presentation
// ---------------------------------------------------------------------------

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <div className="card doc" id={id}>
      <h2 style={{ marginTop: 0 }}>{title}</h2>
      {children}
    </div>
  );
}

type Tab = { label: string; code: string };

/** A code block with language tabs and copy, because a snippet nobody can copy is a screenshot. */
function Snippet({ tabs }: { tabs: Tab[] }) {
  const [active, setActive] = useState(0);
  const [copied, setCopied] = useState(false);
  const current = tabs[active] ?? tabs[0];

  return (
    <div className="snippet">
      <div className="snippet-bar">
        <div className="row" style={{ gap: 4 }}>
          {tabs.map((t, i) => (
            <button
              key={t.label}
              type="button"
              className={i === active ? 'small' : 'ghost small'}
              onClick={() => { setActive(i); setCopied(false); }}
            >
              {t.label}
            </button>
          ))}
        </div>
        <button
          className="ghost small"
          onClick={() => {
            navigator.clipboard?.writeText(current.code);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="code">{current.code}</pre>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

const FLOW = `  your server              browser                  gateway
      │                      │                        │
  1.  ├─ create intent ──────┼───────────────────────▶│   secret key
      │◀───────────────── client_secret ──────────────┤
      │                      │                        │
  2.  ├─ client_secret ─────▶│                        │
  3.  │                      ├─ open() ──────────────▶│   iframe, our origin
      │                      │   card / UPI / wallet  │   card details stay here
      │                      │◀── 3DS if required ───▶│
  4.  │                      │◀── onSuccess ──────────┤   a hint, not proof
      │                      │                        │
  5.  │◀───── webhook: payment_intent.succeeded ──────┤   the proof
      ├─ fulfil the order    │                        │`;

const ENDPOINTS: [string, string][] = [
  ['POST /v1/payment_intents', 'Create a payment. Add confirm:true to authorise in one call'],
  ['GET /v1/payment_intents/{id}', 'Read one, including its rail transactions and ledger'],
  ['GET /v1/payment_intents', 'List, filterable by status, customer and date'],
  ['POST /v1/payment_intents/{id}/capture', 'Claim an authorised payment; supports partial'],
  ['POST /v1/payment_intents/{id}/cancel', 'Release an authorisation before capture'],
  ['POST /v1/refunds', 'Refund a captured payment, fully or partially'],
  ['GET /v1/balance', 'Available and pending funds, plus the trial balance'],
  ['GET /v1/settlements', 'Net settlement batches and their payout dates'],
  ['GET /v1/events', 'Every event emitted, whether or not delivery succeeded'],
  ['GET /v1/public/test_instruments', 'The table above, as JSON. No key needed'],
];

function build(v: {
  base: string;
  publishable: string;
  secretPrefix: string;
  webhookSecret: string;
}) {
  const sk = `${v.secretPrefix}your_key`;

  return {
    create: [
      {
        label: 'cURL',
        code: `curl -X POST ${v.base}/v1/payment_intents \\
  -u ${sk}: \\
  -H 'Idempotency-Key: '"$(uuidgen)" \\
  -d amount=4999 \\
  -d currency=USD \\
  -d description='Order #1234'`,
      },
      {
        label: 'Node',
        code: `// Server side only. This key must never reach a browser.
const res = await fetch('${v.base}/v1/payment_intents', {
  method: 'POST',
  headers: {
    Authorization: 'Basic ' + Buffer.from('${sk}:').toString('base64'),
    'Content-Type': 'application/json',
    'Idempotency-Key': crypto.randomUUID(),
  },
  body: JSON.stringify({
    amount: 4999,              // minor units — $49.99
    currency: 'USD',
    description: 'Order #1234',
    metadata: { order_id: '1234' },
  }),
});

const intent = await res.json();
// Hand ONLY these two to the browser. Never the whole object, never the key.
return { clientSecret: intent.client_secret, intentId: intent.id };`,
      },
      {
        label: 'Java',
        code: `var body = """
    {"amount":4999,"currency":"USD","description":"Order #1234"}""";

var request = HttpRequest.newBuilder(URI.create("${v.base}/v1/payment_intents"))
    .header("Authorization", "Basic " + Base64.getEncoder()
        .encodeToString("${sk}:".getBytes(UTF_8)))
    .header("Content-Type", "application/json")
    .header("Idempotency-Key", UUID.randomUUID().toString())
    .POST(HttpRequest.BodyPublishers.ofString(body))
    .build();

var response = client.send(request, HttpResponse.BodyHandlers.ofString());`,
      },
    ],

    open: [
      {
        label: 'Hosted sheet',
        code: `<script src="${v.base}/mockpay.js"></script>
<script>
  const mockpay = MockPay('${v.publishable}');

  // clientSecret came from your server in step 1.
  document.getElementById('pay').onclick = () => mockpay.open({
    clientSecret,

    // The customer finished. Show a spinner and go ask your own server.
    onSuccess: async (result) => {
      const order = await fetch('/orders/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ paymentIntentId: result.paymentIntentId }),
      }).then((r) => r.json());

      if (order.paid) window.location = '/thank-you';
    },

    // The sheet stays open so they can try another card.
    onFailure: (error) => showMessage(error.message),

    // They dismissed it. The payment may still complete server-side.
    onClose: () => {},
  });
</script>`,
      },
      {
        label: 'Your own form',
        code: `// More control, more PCI scope: the card number passes through YOUR document,
// so a compromised script on your page can read it. That is the Magecart attack,
// and it is the reason the hosted sheet exists. Use this only if you need to.

const { paymentMethod, error } = await mockpay.createPaymentMethod({
  type: 'card',
  card: { number, exp_month, exp_year, cvc },
});
if (error) return showMessage(error.message);

const result = await mockpay.confirmPayment({
  clientSecret,
  paymentMethod: paymentMethod.id,
  onAction: (action) => console.log('customer must complete:', action.type),
});

// Resolves only at a terminal state, including after a 3-D Secure challenge.
if (result.error) showMessage(result.error.message);
else confirmOnYourServer(result.paymentIntent.id);`,
      },
    ],

    webhook: [
      {
        label: 'Node',
        code: `import crypto from 'node:crypto';

const SECRET = process.env.MOCKPAY_WEBHOOK_SECRET;   // ${v.webhookSecret}

// express.raw, NOT express.json — the signature covers the exact bytes we sent.
app.post('/webhooks/mockpay', express.raw({ type: '*/*' }), (req, res) => {
  const header = req.get('MockPay-Signature') || '';
  const parts = Object.fromEntries(header.split(',').map((p) => p.split('=')));

  // Replay window. Without it, one captured delivery works forever.
  if (Math.abs(Date.now() / 1000 - Number(parts.t)) > 300) return res.sendStatus(400);

  const expected = crypto
    .createHmac('sha256', SECRET)
    .update(parts.t + '.' + req.body)        // req.body is a Buffer here
    .digest('hex');

  // Constant time: a fast === leaks the signature one byte at a time. Check the length
  // first, because timingSafeEqual throws rather than returning false on a mismatch.
  const given = Buffer.from(parts.v1 || '', 'utf8');
  const want = Buffer.from(expected, 'utf8');
  if (given.length !== want.length || !crypto.timingSafeEqual(given, want)) {
    return res.sendStatus(400);
  }

  const event = JSON.parse(req.body);

  // Delivery is at-least-once by design, so this must be idempotent.
  if (alreadyHandled(event.id)) return res.sendStatus(200);

  if (event.type === 'payment_intent.succeeded') {
    fulfil(event.data.object.metadata.order_id);
  }

  // 2xx quickly. Do the slow work on a queue — we retry anything that takes too long.
  res.sendStatus(200);
});`,
      },
      {
        label: 'Java',
        code: `// @RequestBody byte[], never a parsed object: Jackson re-serialisation changes
// the bytes and the signature stops matching.
@PostMapping(value = "/webhooks/mockpay", consumes = "*/*")
ResponseEntity<Void> receive(@RequestBody byte[] payload,
                             @RequestHeader("MockPay-Signature") String header) throws Exception {

    Map<String, String> parts = Arrays.stream(header.split(","))
            .map(p -> p.split("=", 2))
            .collect(Collectors.toMap(p -> p[0], p -> p[1]));

    long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(parts.get("t")));
    if (age > 300) return ResponseEntity.badRequest().build();

    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
    mac.update((parts.get("t") + ".").getBytes(UTF_8));
    String expected = HexFormat.of().formatHex(mac.doFinal(payload));

    if (!MessageDigest.isEqual(expected.getBytes(UTF_8), parts.get("v1").getBytes(UTF_8))) {
        return ResponseEntity.badRequest().build();
    }

    handleIdempotently(mapper.readTree(payload));
    return ResponseEntity.ok().build();
}`,
      },
      {
        label: 'Payload',
        code: `POST /webhooks/mockpay
MockPay-Signature: t=1755172800,v1=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08

{
  "id": "evt_1a2b3c4d5e6f",
  "type": "payment_intent.succeeded",
  "created": 1755172800,
  "data": {
    "object": {
      "id": "pi_1a2b3c4d5e6f",
      "amount": 4999,
      "amount_received": 4999,
      "currency": "USD",
      "status": "succeeded",
      "payment_method_type": "card",
      "metadata": { "order_id": "1234" }
    }
  }
}`,
      },
    ],

    idempotency: [
      {
        label: 'Behaviour',
        code: `KEY=$(uuidgen)

# First call — creates the payment.
curl -X POST ${v.base}/v1/payment_intents \\
  -u ${sk}: -H "Idempotency-Key: $KEY" \\
  -d amount=4999 -d currency=USD

# The network dropped the response, so you retry with the SAME key.
# Same payment returned, nothing charged twice.
curl -i -X POST ${v.base}/v1/payment_intents \\
  -u ${sk}: -H "Idempotency-Key: $KEY" \\
  -d amount=4999 -d currency=USD
#   HTTP/1.1 200
#   Idempotent-Replayed: true

# Same key, different body — refused, because a key must mean one thing.
curl -X POST ${v.base}/v1/payment_intents \\
  -u ${sk}: -H "Idempotency-Key: $KEY" \\
  -d amount=9999 -d currency=USD
#   400 idempotency_key_reuse`,
      },
    ],

    errors: [
      {
        label: 'Shape',
        code: `HTTP/1.1 402 Payment Required

{
  "error": {
    "type": "card_error",
    "code": "card_declined",
    "decline_code": "insufficient_funds",
    "message": "Your card has insufficient funds.",
    "payment_intent": { "id": "pi_1a2b3c", "status": "requires_payment_method" }
  }
}`,
      },
    ],
  };
}

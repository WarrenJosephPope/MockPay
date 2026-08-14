/**
 * The one place that talks to the gateway.
 *
 * Two things every mutating request needs, and neither is something a page should have to
 * remember:
 *
 *   1. A CSRF token. The dashboard authenticates with a cookie, which the browser attaches
 *      automatically — so without a token a form on another site could act as the logged-in user.
 *      The token is readable from a cookie and echoed in a header, which an attacker's page can do
 *      neither of.
 *   2. An idempotency key. A dashboard button is double-clicked far more often than an API is
 *      called twice, and without a key that means two API keys, or two refunds.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly type?: string,
  ) {
    super(message);
  }
}

function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

/** A fresh key per call. Generated per *button press*, not per retry — that is the whole point. */
function idempotencyKey(): string {
  return crypto.randomUUID();
}

type Options = {
  method?: string;
  body?: unknown;
  /** Opt out for endpoints where a replayed response would be wrong (there are none today). */
  idempotent?: boolean;
};

async function request<T>(path: string, options: Options = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const mutating = method !== 'GET' && method !== 'HEAD';

  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (mutating) {
    headers['X-XSRF-TOKEN'] = csrfToken();
    if (options.idempotent !== false) headers['Idempotency-Key'] = idempotencyKey();
  }

  const response = await fetch(path, {
    method,
    headers,
    // Send the session cookie. Same-origin in production; the Vite dev server proxies the API so
    // it stays same-origin there too.
    credentials: 'same-origin',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const payload = text ? safeParse(text) : null;

  if (!response.ok) {
    const err = payload?.error;
    throw new ApiError(
      response.status,
      err?.code ?? 'unknown_error',
      err?.message ?? `Request failed (${response.status})`,
      err?.type,
    );
  }
  return payload as T;
}

function safeParse(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};

// ---------------------------------------------------------------------------
// Shapes returned by the gateway. Only the fields the UI actually reads.
// ---------------------------------------------------------------------------

export type Role = 'OWNER' | 'ADMIN' | 'DEVELOPER' | 'VIEWER';

/** Mirrors Membership.Role.atLeast on the server. Used only to hide controls, never to enforce. */
const ROLE_ORDER: Role[] = ['OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'];
export function atLeast(role: Role | undefined, required: Role): boolean {
  if (!role) return false;
  return ROLE_ORDER.indexOf(role) <= ROLE_ORDER.indexOf(required);
}

export type Me = {
  user: { id: string; email: string; name: string };
  merchant: { id: string; name: string; currency: string; country: string };
  role: Role;
  accounts: { merchant_id: string; role: Role }[];
};

export type PaymentIntent = {
  id: string;
  amount: number;
  amount_received?: number;
  amount_refunded?: number;
  currency: string;
  status: string;
  capture_method?: string;
  description?: string;
  customer?: string;
  authorization_code?: string;
  acquirer?: string;
  application_fee_amount?: number;
  payment_method_type?: string;
  created: number;
  risk?: { score: number; level: string };
  three_d_secure?: { status: string; eci: string; liability_shifted: boolean };
  last_payment_error?: { code: string; decline_code?: string; message: string };
  transactions?: RailTransaction[];
  refunds?: Refund[];
  ledger?: LedgerEntry[];
};

export type RailTransaction = {
  id: string;
  type: string;
  outcome: string | null;
  amount: number;
  currency: string;
  mti?: string;
  response_code?: string;
  response_text?: string;
  auth_code?: string;
  rrn?: string;
  rail?: string;
  acquirer?: string;
  latency_ms: number;
  request?: string;
  response?: string;
  created: number;
};

export type Refund = {
  id: string;
  amount: number;
  currency: string;
  status: string;
  reason?: string;
  created: number;
};

export type LedgerEntry = {
  id: string;
  journal: string;
  account: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: number;
  currency: string;
  memo?: string;
};

export type ApiKey = {
  id: string;
  type: 'secret' | 'publishable';
  name?: string;
  prefix: string;
  key?: string;
  warning?: string;
  last_used_at?: number;
  revoked_at?: number;
  created: number;
};

export type WebhookEndpoint = {
  id: string;
  url: string;
  description?: string;
  enabled: boolean;
  enabled_events: string[];
  secret: string;
  consecutive_failures: number;
  created: number;
};

export type EventRow = {
  id: string;
  type: string;
  status: string;
  attempts: number;
  destination?: string;
  last_error?: string;
  created: number;
};

export type TeamMember = {
  membership_id: string;
  user_id?: string;
  email?: string;
  name?: string;
  role: Role;
};

export type Account = {
  id: string;
  name: string;
  mcc: string;
  settlement_currency: string;
  country: string;
  immutable_fields: string[];
  created: number;
};

export type AuditEntry = {
  id: string;
  action: string;
  actor?: string;
  target_type?: string;
  target_id?: string;
  detail?: string;
  ip_address?: string;
  created: number;
};

export type Paged<T> = { data: T[]; has_more: boolean; total_count: number };
export type Listed<T> = { data: T[] };

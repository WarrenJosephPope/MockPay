import { useState } from 'react';
import { api, ApiError, atLeast, type Listed, type Role, type WebhookEndpoint } from '../api';
import { Banner, Empty, Spinner, useLoad, when } from '../ui';

/** Everything the gateway can emit, so the filter is a choice rather than a guess. */
const EVENT_TYPES = [
  'payment_intent.created', 'payment_intent.requires_action', 'payment_intent.authorized',
  'payment_intent.succeeded', 'payment_intent.payment_failed', 'payment_intent.canceled',
  'payment_intent.refunded', 'payment_intent.partially_refunded',
  'refund.succeeded', 'refund.failed',
  'dispute.created', 'dispute.updated', 'dispute.won', 'dispute.lost', 'dispute.closed',
  'settlement.created', 'payout.paid',
];

export default function Endpoints({ role }: { role: Role }) {
  const { data, error, loading, reload } = useLoad<Listed<WebhookEndpoint>>(
    () => api.get('/dashboard/webhook-endpoints'),
  );
  const [url, setUrl] = useState('');
  const [description, setDescription] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [notice, setNotice] = useState<{ kind: 'ok' | 'bad' | 'info'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const canManage = atLeast(role, 'DEVELOPER');

  async function create() {
    setBusy(true);
    setNotice(null);
    try {
      await api.post('/dashboard/webhook-endpoints', {
        url,
        description,
        // Empty means every event. An explicit empty list would mean none, which nobody wants.
        enabled_events: selected.length ? selected : undefined,
      });
      setUrl('');
      setDescription('');
      setSelected([]);
      reload();
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Could not add it.' });
    } finally {
      setBusy(false);
    }
  }

  async function toggle(endpoint: WebhookEndpoint) {
    try {
      await api.patch(`/dashboard/webhook-endpoints/${endpoint.id}`, { enabled: !endpoint.enabled });
      reload();
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Could not update.' });
    }
  }

  async function sendTest(endpoint: WebhookEndpoint) {
    setNotice(null);
    try {
      await api.post(`/dashboard/webhook-endpoints/${endpoint.id}/test`);
      // Queued, not delivered — the dispatcher runs on its own schedule.
      setNotice({
        kind: 'info',
        text: 'Test event queued. Check the event log in a few seconds to see how it went.',
      });
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Could not send.' });
    }
  }

  async function remove(endpoint: WebhookEndpoint) {
    if (!confirm(`Delete ${endpoint.url}? Events will stop being delivered there.`)) return;
    try {
      await api.del(`/dashboard/webhook-endpoints/${endpoint.id}`);
      reload();
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Could not delete.' });
    }
  }

  return (
    <>
      <h1>Webhook endpoints</h1>
      <div className="sub">
        Where the gateway POSTs events. Each endpoint has its own signing secret, so rotating one
        does not break the others.
      </div>

      {notice && <Banner kind={notice.kind}>{notice.text}</Banner>}

      {canManage && (
        <div className="card">
          <label htmlFor="ep-url">URL</label>
          <input id="ep-url" placeholder="https://your-site.example/webhooks/mockpay"
                 value={url} onChange={(e) => setUrl(e.target.value)} />
          <label htmlFor="ep-desc">Description</label>
          <input id="ep-desc" placeholder="Order service" value={description}
                 onChange={(e) => setDescription(e.target.value)} />
          <label>Events ({selected.length === 0 ? 'all' : `${selected.length} selected`})</label>
          <div className="row" style={{ gap: 6 }}>
            {EVENT_TYPES.map((t) => (
              <button key={t} type="button"
                      className={selected.includes(t) ? 'small' : 'ghost small'}
                      onClick={() => setSelected((prev) =>
                        prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t])}>
                {t}
              </button>
            ))}
          </div>
          <button style={{ marginTop: 14 }} onClick={create} disabled={busy || !url}>
            {busy ? 'Adding…' : 'Add endpoint'}
          </button>
        </div>
      )}

      {error && <Banner kind="bad">{error}</Banner>}
      {loading && <Spinner />}
      {data && data.data.length === 0 && <Empty>No endpoints. Events are recorded but not pushed.</Empty>}

      {data?.data.map((e) => (
        <div className="card" key={e.id}>
          <div className="spread">
            <div style={{ minWidth: 0 }}>
              <div style={{ wordBreak: 'break-all' }}>
                <strong>{e.url}</strong>{' '}
                <span className={`pill ${e.enabled ? 'ok' : 'muted'}`}>
                  {e.enabled ? 'enabled' : 'disabled'}
                </span>
              </div>
              <div className="muted" style={{ fontSize: 12.5 }}>
                {e.description || 'No description'} · added {when(e.created)}
                {e.consecutive_failures > 0 && ` · ${e.consecutive_failures} consecutive failures`}
              </div>
            </div>
            {canManage && (
              <div className="row">
                <button className="ghost small" onClick={() => sendTest(e)}>Send test</button>
                <button className="ghost small" onClick={() => toggle(e)}>
                  {e.enabled ? 'Disable' : 'Enable'}
                </button>
                <button className="danger small" onClick={() => remove(e)}>Delete</button>
              </div>
            )}
          </div>

          <dl className="kv" style={{ marginTop: 12 }}>
            <dt>Signing secret</dt>
            <dd><code style={{ wordBreak: 'break-all' }}>{e.secret}</code></dd>
            <dt>Events</dt>
            <dd>
              {e.enabled_events.includes('*')
                ? 'All events'
                : e.enabled_events.join(', ')}
            </dd>
          </dl>
        </div>
      ))}

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Verifying a delivery</h2>
        <div className="muted" style={{ fontSize: 13 }}>
          The <code>MockPay-Signature</code> header is <code>t=&lt;unix&gt;,v1=&lt;hmac&gt;</code>,
          where the HMAC-SHA256 is over <code>"&#123;timestamp&#125;.&#123;raw body&#125;"</code>
          using that endpoint's secret. Verify against the <strong>raw bytes</strong> — re-serialising
          the JSON changes them and the signature will not match. Reject timestamps older than five
          minutes, and deduplicate on the event id.
        </div>
      </div>
    </>
  );
}

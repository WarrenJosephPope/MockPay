import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, atLeast, type PaymentIntent, type Role } from '../api';
import { Banner, Spinner, Status, money, useLoad, when } from '../ui';

export default function PaymentDetail({ role }: { role: Role }) {
  const { id } = useParams();
  const { data, error, loading, reload } = useLoad<PaymentIntent>(
    () => api.get(`/dashboard/payments/${id}`), [id],
  );
  const [notice, setNotice] = useState<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  const [refundAmount, setRefundAmount] = useState('');
  const [busy, setBusy] = useState(false);

  async function refund() {
    setBusy(true);
    setNotice(null);
    try {
      const amount = refundAmount.trim() ? Number(refundAmount.trim()) : undefined;
      await api.post('/dashboard/refunds', { payment_intent: id, amount, reason: 'requested_by_customer' });
      setNotice({ kind: 'ok', text: 'Refund issued.' });
      setRefundAmount('');
      reload();
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Refund failed.' });
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <Spinner />;
  if (error) return <Banner kind="bad">{error}</Banner>;
  if (!data) return null;

  const refundable = (data.amount_received ?? 0) - (data.amount_refunded ?? 0);
  // Refunds move money, so DEVELOPER is deliberately not enough. The server enforces this too.
  const canRefund = atLeast(role, 'ADMIN') && data.status === 'succeeded' && refundable > 0;

  return (
    <>
      <div className="sub"><Link to="/payments">← Payments</Link></div>
      <h1>{money(data.amount, data.currency)} <Status value={data.status} /></h1>
      <div className="sub"><code>{data.id}</code></div>

      {notice && <Banner kind={notice.kind}>{notice.text}</Banner>}

      {data.last_payment_error && (
        <Banner kind="bad">
          <strong>{data.last_payment_error.decline_code || data.last_payment_error.code}</strong>
          {' — '}{data.last_payment_error.message}
        </Banner>
      )}

      <div className="card">
        <dl className="kv">
          <dt>Description</dt><dd>{data.description || '—'}</dd>
          <dt>Customer</dt><dd>{data.customer || '—'}</dd>
          <dt>Created</dt><dd>{when(data.created)}</dd>
          <dt>Capture method</dt><dd>{data.capture_method ?? '—'}</dd>
          <dt>Received</dt><dd>{money(data.amount_received ?? 0, data.currency)}</dd>
          <dt>Refunded</dt><dd>{money(data.amount_refunded ?? 0, data.currency)}</dd>
          <dt>Fee</dt><dd>{money(data.application_fee_amount ?? 0, data.currency)}</dd>
          <dt>Authorisation code</dt><dd><code>{data.authorization_code ?? '—'}</code></dd>
          <dt>Acquirer</dt><dd>{data.acquirer ?? '—'}</dd>
          {data.risk && (
            <>
              <dt>Risk</dt>
              <dd>{data.risk.level} ({data.risk.score})</dd>
            </>
          )}
          {data.three_d_secure && (
            <>
              <dt>3-D Secure</dt>
              <dd>
                {data.three_d_secure.status}
                {data.three_d_secure.liability_shifted
                  ? ' — liability shifted to the issuer'
                  : ' — merchant retains liability'}
              </dd>
            </>
          )}
        </dl>
      </div>

      {canRefund && (
        <div className="card">
          <h2 style={{ marginTop: 0 }}>Refund</h2>
          <div className="muted" style={{ fontSize: 13, marginBottom: 10 }}>
            Up to {money(refundable, data.currency)}. A refund is a new transaction in the opposite
            direction, not an undo — it settles separately and the original fee is not returned.
          </div>
          <div className="row">
            <input style={{ maxWidth: 200 }} inputMode="numeric"
                   placeholder={`Full (${refundable})`} value={refundAmount}
                   onChange={(e) => setRefundAmount(e.target.value)} />
            <button onClick={refund} disabled={busy}>
              {busy ? 'Refunding…' : 'Issue refund'}
            </button>
          </div>
        </div>
      )}

      {data.refunds && data.refunds.length > 0 && (
        <div className="card" style={{ padding: 0 }}>
          <div className="pad"><strong>Refunds</strong></div>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Amount</th><th>Status</th><th>Reason</th><th>When</th></tr></thead>
              <tbody>
                {data.refunds.map((r) => (
                  <tr key={r.id}>
                    <td>{money(r.amount, r.currency)}</td>
                    <td><Status value={r.status} /></td>
                    <td className="muted">{r.reason ?? '—'}</td>
                    <td className="muted">{when(r.created)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <h2>Rail trace</h2>
      <div className="sub">
        The actual messages exchanged with the simulated network. No production gateway shows a
        merchant this — it is what their support engineers see.
      </div>
      {(data.transactions ?? []).map((t) => (
        <div className="card" key={t.id}>
          <div className="trace-head">
            <strong>{t.type.toUpperCase()}</strong>
            <Status value={t.outcome ?? 'unknown'} />
            <span className="muted">{t.rail}</span>
            <span className="muted">{t.latency_ms}ms</span>
            {t.response_code && (
              <span className="muted">DE39 {t.response_code} — {t.response_text}</span>
            )}
          </div>
          {t.request && <pre className="trace">{t.request}</pre>}
          {t.response && (
            <>
              <div className="trace-head muted">response</div>
              <pre className="trace">{t.response}</pre>
            </>
          )}
        </div>
      ))}

      {data.ledger && data.ledger.length > 0 && (
        <>
          <h2>Ledger</h2>
          <div className="sub">
            Double-entry journals. Each journal sums to zero — money is only ever moved between
            accounts, never created.
          </div>
          <div className="card" style={{ padding: 0 }}>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>Journal</th><th>Account</th><th>Direction</th>
                      <th className="right">Amount</th><th>Memo</th></tr>
                </thead>
                <tbody>
                  {data.ledger.map((e) => (
                    <tr key={e.id}>
                      <td><code>{e.journal.slice(0, 12)}…</code></td>
                      <td>{e.account}</td>
                      <td>
                        <span className={`pill ${e.direction === 'DEBIT' ? 'muted' : 'ok'}`}>
                          {e.direction}
                        </span>
                      </td>
                      <td className="right">{money(e.amount, e.currency)}</td>
                      <td className="muted">{e.memo ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </>
  );
}

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, type Paged, type PaymentIntent } from '../api';
import { Banner, Empty, Spinner, Status, money, useLoad, when } from '../ui';

const STATUSES = [
  '', 'succeeded', 'requires_capture', 'requires_action', 'requires_payment_method',
  'requires_confirmation', 'processing', 'failed', 'canceled',
];

type Filters = {
  status: string; created_from: string; created_to: string;
  amount_min: string; amount_max: string; last4: string; query: string;
};

const EMPTY: Filters = {
  status: '', created_from: '', created_to: '', amount_min: '', amount_max: '', last4: '', query: '',
};

export default function Payments() {
  const navigate = useNavigate();
  // `draft` is what the form holds; `applied` is what the server was asked for. Keeping them
  // separate stops every keystroke from firing a request.
  const [draft, setDraft] = useState<Filters>(EMPTY);
  const [applied, setApplied] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(0);

  const query = new URLSearchParams({ page: String(page), limit: '25' });
  (Object.keys(applied) as (keyof Filters)[]).forEach((k) => {
    const v = applied[k].trim();
    if (v) query.set(k, v);
  });

  const { data, error, loading } = useLoad<Paged<PaymentIntent>>(
    () => api.get(`/dashboard/payments?${query.toString()}`),
    [query.toString()],
  );

  const set = (k: keyof Filters) => (e: { target: { value: string } }) =>
    setDraft({ ...draft, [k]: e.target.value });

  function apply(e: React.FormEvent) {
    e.preventDefault();
    setPage(0);
    setApplied(draft);
  }

  function clear() {
    setDraft(EMPTY);
    setApplied(EMPTY);
    setPage(0);
  }

  const active = Object.values(applied).some((v) => v.trim() !== '');

  return (
    <>
      <h1>Payments</h1>
      <div className="sub">
        Every payment on this account. Filters compose — dates are UTC calendar days.
      </div>

      <form className="card" onSubmit={apply}>
        <div className="filters">
          <div>
            <label htmlFor="f-status">Status</label>
            <select id="f-status" value={draft.status} onChange={set('status')}>
              {STATUSES.map((s) => <option key={s} value={s}>{s || 'Any'}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="f-from">From</label>
            <input id="f-from" type="date" value={draft.created_from} onChange={set('created_from')} />
          </div>
          <div>
            <label htmlFor="f-to">To</label>
            <input id="f-to" type="date" value={draft.created_to} onChange={set('created_to')} />
          </div>
          <div>
            <label htmlFor="f-min">Min (minor units)</label>
            <input id="f-min" inputMode="numeric" placeholder="1000" value={draft.amount_min}
                   onChange={set('amount_min')} />
          </div>
          <div>
            <label htmlFor="f-max">Max</label>
            <input id="f-max" inputMode="numeric" placeholder="50000" value={draft.amount_max}
                   onChange={set('amount_max')} />
          </div>
          <div>
            <label htmlFor="f-last4">Card last 4</label>
            <input id="f-last4" inputMode="numeric" maxLength={4} placeholder="4242"
                   value={draft.last4} onChange={set('last4')} />
          </div>
          <div>
            <label htmlFor="f-q">Search</label>
            <input id="f-q" placeholder="description, customer, or pi_…" value={draft.query}
                   onChange={set('query')} />
          </div>
          <div className="row">
            <button type="submit">Apply</button>
            {active && <button type="button" className="ghost" onClick={clear}>Clear</button>}
          </div>
        </div>
      </form>

      {error && <Banner kind="bad">{error}</Banner>}
      {loading && <Spinner />}

      {data && data.data.length === 0 && (
        <Empty>
          {active ? 'No payments match those filters.' : 'No payments yet. Try the demo checkout.'}
        </Empty>
      )}

      {data && data.data.length > 0 && (
        <>
          <div className="card" style={{ padding: 0 }}>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Amount</th><th>Status</th><th>Description</th>
                    <th>Method</th><th>Created</th><th className="right">Payment</th>
                  </tr>
                </thead>
                <tbody>
                  {data.data.map((p) => (
                    <tr key={p.id} className="clickable" onClick={() => navigate(`/payments/${p.id}`)}>
                      <td><strong>{money(p.amount, p.currency)}</strong></td>
                      <td><Status value={p.status} /></td>
                      <td>
                        {p.description || <span className="muted">—</span>}
                        {p.last_payment_error && (
                          <div className="muted" style={{ fontSize: 12 }}>
                            {p.last_payment_error.decline_code || p.last_payment_error.code}
                          </div>
                        )}
                      </td>
                      <td className="muted">{p.payment_method_type ?? '—'}</td>
                      <td className="muted">{when(p.created)}</td>
                      <td className="right"><code>{p.id}</code></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="spread">
            <span className="muted">
              {data.total_count} payment{data.total_count === 1 ? '' : 's'}
            </span>
            <div className="row">
              <button className="ghost small" disabled={page === 0}
                      onClick={() => setPage((n) => n - 1)}>Previous</button>
              <span className="muted">Page {page + 1}</span>
              <button className="ghost small" disabled={!data.has_more}
                      onClick={() => setPage((n) => n + 1)}>Next</button>
            </div>
          </div>
        </>
      )}
    </>
  );
}

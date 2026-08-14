import { useEffect, useState } from 'react';
import { api, ApiError, atLeast, type Account, type Role } from '../api';
import { Banner, Spinner, useLoad, when } from '../ui';

export default function Settings({ role }: { role: Role }) {
  const { data, error, loading, reload } = useLoad<Account>(() => api.get('/dashboard/account'));
  const [name, setName] = useState('');
  const [mcc, setMcc] = useState('');
  const [notice, setNotice] = useState<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (data) {
      setName(data.name);
      setMcc(data.mcc);
    }
  }, [data]);

  const canEdit = atLeast(role, 'ADMIN');

  async function save() {
    setBusy(true);
    setNotice(null);
    try {
      await api.patch('/dashboard/account', { name, mcc });
      setNotice({ kind: 'ok', text: 'Saved.' });
      reload();
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Could not save.' });
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <Spinner />;
  if (error) return <Banner kind="bad">{error}</Banner>;
  if (!data) return null;

  return (
    <>
      <h1>Settings</h1>
      <div className="sub">Account profile and integration details.</div>

      {notice && <Banner kind={notice.kind}>{notice.text}</Banner>}

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Business</h2>
        <label htmlFor="s-name">Name</label>
        <input id="s-name" value={name} onChange={(e) => setName(e.target.value)} disabled={!canEdit} />
        <label htmlFor="s-mcc">Merchant category code</label>
        <input id="s-mcc" value={mcc} onChange={(e) => setMcc(e.target.value)}
               disabled={!canEdit} maxLength={4} />
        <div className="muted" style={{ fontSize: 12.5, marginTop: 6 }}>
          ISO 18245, four digits. Drives interchange rates and issuer risk models — a mismatched
          code costs approval rate and money.
        </div>
        {canEdit && (
          <button style={{ marginTop: 14 }} onClick={save} disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        )}
      </div>

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Fixed at signup</h2>
        <dl className="kv">
          <dt>Account id</dt><dd><code>{data.id}</code></dd>
          <dt>Settlement currency</dt><dd>{data.settlement_currency}</dd>
          <dt>Country</dt><dd>{data.country}</dd>
          <dt>Created</dt><dd>{when(data.created)}</dd>
        </dl>
        <div className="muted" style={{ fontSize: 12.5, marginTop: 12 }}>
          Neither can be changed. Every payment, ledger entry and settlement on this account is
          denominated in {data.settlement_currency} — relabelling them would not convert anything,
          it would misstate every balance. Country determines acquirer routing, and existing
          authorisations were routed on the current value. Open a second account instead.
        </div>
      </div>

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Integrating</h2>
        <div className="muted" style={{ fontSize: 13 }}>
          <p style={{ marginTop: 0 }}>
            Your server calls <code>/v1</code> with a secret key. Your checkout page calls{' '}
            <code>/v1/public</code> with the publishable key and a payment intent's client secret,
            so card numbers never reach your backend.
          </p>
          <p>
            Learn the outcome of a payment from the <strong>webhook</strong>, never from the browser
            returning — customers close tabs, and the payment still succeeded.
          </p>
        </div>
      </div>
    </>
  );
}

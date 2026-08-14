import { useState } from 'react';
import { api, ApiError, atLeast, type ApiKey, type Listed, type Role } from '../api';
import { Banner, Empty, RevealOnce, Spinner, Status, useLoad, when } from '../ui';

export default function ApiKeys({ role }: { role: Role }) {
  const { data, error, loading, reload } = useLoad<Listed<ApiKey>>(() => api.get('/dashboard/api-keys'));
  const [issued, setIssued] = useState<ApiKey | null>(null);
  const [name, setName] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const canManage = atLeast(role, 'ADMIN');

  async function create() {
    setBusy(true);
    setNotice(null);
    try {
      // The API client attaches an Idempotency-Key automatically, so a double click cannot produce
      // two keys — one of which the operator would never see again and could not tell from a leak.
      setIssued(await api.post<ApiKey>('/dashboard/api-keys', {
        type: 'secret',
        name: name || 'Untitled',
      }));
      setName('');
      reload();
    } catch (err) {
      setNotice(err instanceof ApiError ? err.message : 'Could not create the key.');
    } finally {
      setBusy(false);
    }
  }

  async function revoke(key: ApiKey) {
    if (!confirm(`Revoke ${key.prefix}…? Anything using it stops working immediately.`)) return;
    setNotice(null);
    try {
      await api.post(`/dashboard/api-keys/${key.id}/revoke`);
      reload();
    } catch (err) {
      setNotice(err instanceof ApiError ? err.message : 'Could not revoke the key.');
    }
  }

  return (
    <>
      <h1>API keys</h1>
      <div className="sub">
        Secret keys are stored as hashes. A new one is readable exactly once, in the response that
        creates it.
      </div>

      {notice && <Banner kind="bad">{notice}</Banner>}

      {issued?.key && (
        <div className="card">
          <Banner kind="bad">
            Copy this now. Only its hash is stored, so it cannot be shown again.
          </Banner>
          <RevealOnce label={issued.name || 'New secret key'} value={issued.key} />
          <button className="ghost" onClick={() => setIssued(null)}>Done</button>
        </div>
      )}

      {canManage && (
        <div className="card">
          <div className="row">
            <input style={{ maxWidth: 280 }} placeholder="Label, e.g. CI or production"
                   value={name} onChange={(e) => setName(e.target.value)} />
            <button onClick={create} disabled={busy}>
              {busy ? 'Creating…' : 'Create secret key'}
            </button>
          </div>
          <div className="muted" style={{ fontSize: 12.5, marginTop: 8 }}>
            To rotate without downtime: create the replacement, deploy it, watch “last used” on the
            old key stop moving, then revoke it.
          </div>
        </div>
      )}

      {error && <Banner kind="bad">{error}</Banner>}
      {loading && <Spinner />}
      {data && data.data.length === 0 && <Empty>No keys.</Empty>}

      {data && data.data.length > 0 && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Key</th><th>Type</th><th>Label</th><th>Last used</th><th>Created</th><th /></tr>
              </thead>
              <tbody>
                {data.data.map((k) => (
                  <tr key={k.id}>
                    <td><code>{k.key ?? `${k.prefix}…`}</code></td>
                    <td>
                      <span className={`pill ${k.type === 'secret' ? 'warn' : 'muted'}`}>{k.type}</span>
                      {k.revoked_at ? <> <Status value="revoked" /></> : null}
                    </td>
                    <td>{k.name ?? '—'}</td>
                    <td className="muted">{k.last_used_at ? when(k.last_used_at) : 'never'}</td>
                    <td className="muted">{when(k.created)}</td>
                    <td className="right">
                      {canManage && !k.revoked_at && k.type === 'secret' && (
                        <button className="danger small" onClick={() => revoke(k)}>Revoke</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}

import { api, type AuditEntry, type Paged } from '../api';
import { Banner, Empty, Spinner, useLoad, when } from '../ui';

export default function AuditLog() {
  const { data, error, loading } = useLoad<Paged<AuditEntry>>(() => api.get('/dashboard/audit-log'));

  return (
    <>
      <h1>Audit log</h1>
      <div className="sub">
        Who did what, when, and from where. Append-only — nothing in the system edits or deletes
        these rows, because a log that can be altered is not evidence of anything.
      </div>

      {error && <Banner kind="bad">{error}</Banner>}
      {loading && <Spinner />}
      {data && data.data.length === 0 && <Empty>Nothing recorded yet.</Empty>}

      {data && data.data.length > 0 && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Action</th><th>Actor</th><th>Target</th><th>Detail</th>
                    <th>IP</th><th>When</th></tr>
              </thead>
              <tbody>
                {data.data.map((a) => (
                  <tr key={a.id}>
                    <td><code>{a.action}</code></td>
                    <td>{a.actor ?? <span className="muted">system</span>}</td>
                    <td className="muted">
                      {a.target_type}
                      {a.target_id && <div><code>{a.target_id}</code></div>}
                    </td>
                    <td className="muted" style={{ fontSize: 12.5 }}>{a.detail ?? '—'}</td>
                    <td className="muted">{a.ip_address ?? '—'}</td>
                    <td className="muted">{when(a.created)}</td>
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

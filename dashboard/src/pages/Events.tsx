import { useState } from 'react';
import { api, ApiError, type EventRow, type Listed } from '../api';
import { Banner, Empty, Spinner, Status, useLoad, when } from '../ui';

export default function Events() {
  const { data, error, loading, reload } = useLoad<Listed<EventRow>>(() => api.get('/dashboard/events'));
  const [notice, setNotice] = useState<string | null>(null);

  async function replay(id: string) {
    setNotice(null);
    try {
      await api.post(`/dashboard/events/${id}/replay`);
      setNotice('Queued for redelivery. The dispatcher will pick it up within a few seconds.');
      reload();
    } catch (err) {
      setNotice(err instanceof ApiError ? err.message : 'Could not replay.');
    }
  }

  return (
    <>
      <h1>Event log</h1>
      <div className="sub">
        Every webhook the gateway has tried to deliver. Delivery is at-least-once, so your handler
        must deduplicate on the event id.
      </div>

      {notice && <Banner kind="info">{notice}</Banner>}
      {error && <Banner kind="bad">{error}</Banner>}
      {loading && <Spinner />}
      {data && data.data.length === 0 && <Empty>No events yet.</Empty>}

      {data && data.data.length > 0 && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Type</th><th>Status</th><th>Attempts</th><th>Destination</th>
                    <th>When</th><th /></tr>
              </thead>
              <tbody>
                {data.data.map((e) => (
                  <tr key={e.id}>
                    <td><code>{e.type}</code></td>
                    <td>
                      <Status value={e.status} />
                      {e.last_error && (
                        <div className="muted" style={{ fontSize: 12 }}>{e.last_error}</div>
                      )}
                    </td>
                    <td className="muted">{e.attempts}</td>
                    <td className="muted" style={{ wordBreak: 'break-all' }}>{e.destination ?? '—'}</td>
                    <td className="muted">{when(e.created)}</td>
                    <td className="right">
                      <button className="ghost small" onClick={() => replay(e.id)}>Replay</button>
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

import { useState } from 'react';
import { api, ApiError, atLeast, type Role, type TeamMember } from '../api';
import { Banner, RevealOnce, Spinner, useLoad, when } from '../ui';

type TeamResponse = {
  data: TeamMember[];
  pending_invitations: { id: string; email: string; role: Role; expires: number }[];
};

const ROLES: { value: Role; blurb: string }[] = [
  { value: 'OWNER', blurb: 'Everything, including managing the team' },
  { value: 'ADMIN', blurb: 'Issue keys and refunds; cannot manage the team' },
  { value: 'DEVELOPER', blurb: 'Integrations only — cannot move money' },
  { value: 'VIEWER', blurb: 'Read-only' },
];

export default function Team({ role, meUserId }: { role: Role; meUserId: string }) {
  const { data, error, loading, reload } = useLoad<TeamResponse>(() => api.get('/dashboard/team'));
  const [email, setEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<Role>('VIEWER');
  const [notice, setNotice] = useState<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  const [inviteToken, setInviteToken] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // OWNER only: inviting someone is granting authority, so an ADMIN who could invite an OWNER
  // would be able to escalate past their own role.
  const canManage = atLeast(role, 'OWNER');

  async function invite() {
    setBusy(true);
    setNotice(null);
    setInviteToken(null);
    try {
      const created = await api.post<{ token?: string }>('/dashboard/team/invitations', {
        email, role: inviteRole,
      });
      setEmail('');
      // The token comes back only while no SMTP server is configured; otherwise it exists solely
      // in the invitee's inbox.
      if (created.token) setInviteToken(created.token);
      else setNotice({ kind: 'ok', text: 'Invitation emailed.' });
      reload();
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Could not invite.' });
    } finally {
      setBusy(false);
    }
  }

  async function changeRole(member: TeamMember, next: Role) {
    setNotice(null);
    try {
      await api.patch(`/dashboard/team/${member.membership_id}`, { role: next });
      reload();
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Could not update.' });
    }
  }

  async function remove(member: TeamMember) {
    if (!confirm(`Remove ${member.email}? Their session ends on their next request.`)) return;
    setNotice(null);
    try {
      await api.del(`/dashboard/team/${member.membership_id}`);
      reload();
    } catch (err) {
      setNotice({ kind: 'bad', text: err instanceof ApiError ? err.message : 'Could not remove.' });
    }
  }

  return (
    <>
      <h1>Team</h1>
      <div className="sub">Who can act on this account, and with what authority.</div>

      {notice && <Banner kind={notice.kind}>{notice.text}</Banner>}

      {inviteToken && (
        <div className="card">
          <Banner kind="ok">
            Invitation created. No SMTP server is configured, so the link is shown here instead of
            being emailed — send it to them yourself.
          </Banner>
          <RevealOnce
            label="Invitation link"
            value={`${window.location.origin}/accept-invitation?token=${inviteToken}`}
          />
          <button className="ghost" onClick={() => setInviteToken(null)}>Done</button>
        </div>
      )}

      {canManage && (
        <div className="card">
          <div className="row">
            <input style={{ maxWidth: 280 }} type="email" placeholder="teammate@example.com"
                   value={email} onChange={(e) => setEmail(e.target.value)} />
            <select style={{ maxWidth: 160 }} value={inviteRole}
                    onChange={(e) => setInviteRole(e.target.value as Role)}>
              {ROLES.map((r) => <option key={r.value} value={r.value}>{r.value}</option>)}
            </select>
            <button onClick={invite} disabled={busy || !email}>
              {busy ? 'Inviting…' : 'Send invitation'}
            </button>
          </div>
          <div className="muted" style={{ fontSize: 12.5, marginTop: 8 }}>
            {ROLES.find((r) => r.value === inviteRole)?.blurb}. Invitations expire after seven days
            and can be used once.
          </div>
        </div>
      )}

      {error && <Banner kind="bad">{error}</Banner>}
      {loading && <Spinner />}

      {data && (
        <div className="card" style={{ padding: 0 }}>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Member</th><th>Role</th><th /></tr></thead>
              <tbody>
                {data.data.map((m) => (
                  <tr key={m.membership_id}>
                    <td>
                      <strong>{m.name || m.email}</strong>
                      <div className="muted" style={{ fontSize: 12.5 }}>{m.email}</div>
                    </td>
                    <td>
                      {canManage && m.user_id !== meUserId ? (
                        <select value={m.role} style={{ maxWidth: 150 }}
                                onChange={(e) => changeRole(m, e.target.value as Role)}>
                          {ROLES.map((r) => <option key={r.value} value={r.value}>{r.value}</option>)}
                        </select>
                      ) : (
                        <span className="pill muted">{m.role}</span>
                      )}
                    </td>
                    <td className="right">
                      {canManage && m.user_id !== meUserId && (
                        <button className="danger small" onClick={() => remove(m)}>Remove</button>
                      )}
                      {m.user_id === meUserId && <span className="muted">you</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {data && data.pending_invitations.length > 0 && (
        <>
          <h2>Pending invitations</h2>
          <div className="card" style={{ padding: 0 }}>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Email</th><th>Role</th><th>Expires</th></tr></thead>
                <tbody>
                  {data.pending_invitations.map((i) => (
                    <tr key={i.id}>
                      <td>{i.email}</td>
                      <td><span className="pill muted">{i.role}</span></td>
                      <td className="muted">{when(i.expires)}</td>
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

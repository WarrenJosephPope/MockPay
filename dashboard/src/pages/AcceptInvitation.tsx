import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { Banner } from '../ui';

export default function AcceptInvitation({ onAuthenticated }: { onAuthenticated: () => void }) {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/dashboard/auth/accept-invitation', { token, password, name });
      onAuthenticated();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not accept the invitation.');
    } finally {
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <div className="auth"><div className="card">
        <h1>Join a team</h1>
        <Banner kind="bad">This link is missing its token. Ask for a new invitation.</Banner>
        <Link to="/login">Back to sign in</Link>
      </div></div>
    );
  }

  return (
    <div className="auth">
      <div className="card">
        <h1>Join the team</h1>
        <div className="sub">
          You were invited by email. Your address comes from the invitation, not from this form.
        </div>
        {error && <Banner kind="bad">{error}</Banner>}
        <form onSubmit={submit}>
          <label htmlFor="ai-name">Your name</label>
          <input id="ai-name" value={name} onChange={(e) => setName(e.target.value)} />
          <label htmlFor="ai-password">Password</label>
          <input id="ai-password" type="password" autoComplete="new-password" value={password}
                 onChange={(e) => setPassword(e.target.value)} minLength={12} />
          <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
            Leave blank if you already have a MockPay account with the invited address.
          </div>
          <button style={{ width: '100%', marginTop: 18 }} disabled={busy}>
            {busy ? 'Joining…' : 'Accept invitation'}
          </button>
        </form>
      </div>
    </div>
  );
}

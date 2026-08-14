import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { Banner } from '../ui';

export default function ResetPassword() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/dashboard/auth/reset-password', { token, password });
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not reset the password.');
    } finally {
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <div className="auth"><div className="card">
        <h1>Reset your password</h1>
        <Banner kind="bad">This link is missing its token. Request a new one.</Banner>
        <Link to="/forgot-password">Request a new link</Link>
      </div></div>
    );
  }

  return (
    <div className="auth">
      <div className="card">
        <h1>Choose a new password</h1>
        {done ? (
          <>
            <Banner kind="ok">
              Password updated. Every other session has been signed out.
            </Banner>
            <Link to="/login">Sign in</Link>
          </>
        ) : (
          <>
            {error && <Banner kind="bad">{error}</Banner>}
            <form onSubmit={submit}>
              <label htmlFor="rp-password">New password</label>
              <input id="rp-password" type="password" autoComplete="new-password" value={password}
                     onChange={(e) => setPassword(e.target.value)} required minLength={12} />
              <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
                At least 12 characters. Using this link signs you out everywhere.
              </div>
              <button style={{ width: '100%', marginTop: 18 }} disabled={busy}>
                {busy ? 'Updating…' : 'Set new password'}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}

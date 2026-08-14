import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { Banner } from '../ui';

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api.post('/dashboard/auth/forgot-password', { email });
    } finally {
      // Always the same outcome, even on failure. The endpoint deliberately reveals nothing about
      // whether the address is registered, and the UI must not undo that by behaving differently.
      setSent(true);
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <div className="card">
        <h1>Reset your password</h1>
        {sent ? (
          <>
            <Banner kind="ok">
              If an account exists for that address, a reset link has been sent. It expires in an
              hour and can be used once.
            </Banner>
            <div className="muted" style={{ fontSize: 12.5 }}>
              No mail server configured? The message is written to the application log instead.
            </div>
            <div style={{ marginTop: 14 }}><Link to="/login">Back to sign in</Link></div>
          </>
        ) : (
          <>
            <div className="sub">We'll email you a link to set a new one.</div>
            <form onSubmit={submit}>
              <label htmlFor="fp-email">Email</label>
              <input id="fp-email" type="email" value={email}
                     onChange={(e) => setEmail(e.target.value)} required />
              <button style={{ width: '100%', marginTop: 18 }} disabled={busy}>
                {busy ? 'Sending…' : 'Send reset link'}
              </button>
            </form>
            <div style={{ marginTop: 14 }}><Link to="/login">Back to sign in</Link></div>
          </>
        )}
      </div>
    </div>
  );
}

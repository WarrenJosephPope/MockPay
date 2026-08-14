import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api';
import { Banner, RevealOnce } from '../ui';

type SignupResponse = {
  merchant_id: string;
  secret_key: string;
  publishable_key: string;
};

export default function Signup({ onAuthenticated }: { onAuthenticated: () => void }) {
  const [form, setForm] = useState({
    email: '', password: '', name: '', business_name: '', currency: 'USD', country: 'US',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [keys, setKeys] = useState<SignupResponse | null>(null);

  const set = (k: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm({ ...form, [k]: e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setKeys(await api.post<SignupResponse>('/dashboard/auth/signup', form));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create the account.');
    } finally {
      setBusy(false);
    }
  }

  // The secret key is readable exactly once, in this response. Dropping the user straight into the
  // dashboard would lose it, so the keys are shown first and continuing is an explicit act.
  if (keys) {
    return (
      <div className="auth">
        <div className="card">
          <h1>Save your keys</h1>
          <Banner kind="bad">
            The secret key is shown <strong>once</strong>. It cannot be recovered — only its hash is
            stored. Copy it before continuing.
          </Banner>
          <RevealOnce label="Secret key (server-side only)" value={keys.secret_key} />
          <RevealOnce label="Publishable key (safe in a browser)" value={keys.publishable_key} />
          <button style={{ width: '100%', marginTop: 12 }} onClick={onAuthenticated}>
            I have saved the secret key — continue
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="auth">
      <div className="card">
        <h1>Create an account</h1>
        <div className="sub">Sets up a business and its first API keys.</div>
        {error && <Banner kind="bad">{error}</Banner>}
        <form onSubmit={submit}>
          <label htmlFor="su-name">Your name</label>
          <input id="su-name" value={form.name} onChange={set('name')} required />
          <label htmlFor="su-business">Business name</label>
          <input id="su-business" value={form.business_name} onChange={set('business_name')} required />
          <div className="row">
            <div style={{ flex: 1 }}>
              <label htmlFor="su-currency">Settlement currency</label>
              <select id="su-currency" value={form.currency} onChange={set('currency')}>
                <option>USD</option><option>EUR</option><option>GBP</option><option>INR</option>
              </select>
            </div>
            <div style={{ flex: 1 }}>
              <label htmlFor="su-country">Country</label>
              <select id="su-country" value={form.country} onChange={set('country')}>
                <option>US</option><option>GB</option><option>DE</option><option>IN</option>
              </select>
            </div>
          </div>
          <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
            Neither can be changed later — every payment and ledger entry is denominated in the
            settlement currency.
          </div>
          <label htmlFor="su-email">Email</label>
          <input id="su-email" type="email" autoComplete="username" value={form.email}
                 onChange={set('email')} required />
          <label htmlFor="su-password">Password</label>
          <input id="su-password" type="password" autoComplete="new-password" value={form.password}
                 onChange={set('password')} required minLength={12} />
          <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
            At least 12 characters. Length matters more than symbols.
          </div>
          <button style={{ width: '100%', marginTop: 18 }} disabled={busy}>
            {busy ? 'Creating…' : 'Create account'}
          </button>
        </form>
        <div style={{ marginTop: 14 }}>
          Already have an account? <Link to="/login">Sign in</Link>
        </div>
      </div>
    </div>
  );
}

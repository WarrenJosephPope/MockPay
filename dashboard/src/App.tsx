import { useEffect, useState } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { api, atLeast, type Me } from './api';
import { Spinner } from './ui';
import Login from './pages/Login';
import Signup from './pages/Signup';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import AcceptInvitation from './pages/AcceptInvitation';
import Payments from './pages/Payments';
import PaymentDetail from './pages/PaymentDetail';
import ApiKeys from './pages/ApiKeys';
import Endpoints from './pages/Endpoints';
import Events from './pages/Events';
import Team from './pages/Team';
import Settings from './pages/Settings';
import AuditLog from './pages/AuditLog';
import Docs from './pages/Docs';

/**
 * Everything below the router.
 *
 * `me` is the single source of truth for identity and role. It is fetched once on mount and
 * refetched after anything that could change it — the server re-reads the membership on every
 * request anyway, so this is only about what the UI *shows*, never about what it permits.
 */
function Shell({ me, onSignedOut }: { me: Me; onSignedOut: () => void }) {
  const navigate = useNavigate();

  async function signOut() {
    try {
      await api.post('/dashboard/auth/logout');
    } finally {
      onSignedOut();
      navigate('/login');
    }
  }

  // Links are hidden by role purely as a courtesy. The server enforces every one of these
  // independently — hiding a button is not access control.
  const canSeeKeys = atLeast(me.role, 'DEVELOPER');
  const canSeeAudit = atLeast(me.role, 'ADMIN');

  return (
    <div className="shell">
      <aside className="side">
        <div className="brand">MockPay</div>
        <nav>
          <NavLink to="/payments">Payments</NavLink>
          {canSeeKeys && <NavLink to="/api-keys">API keys</NavLink>}
          {canSeeKeys && <NavLink to="/endpoints">Webhook endpoints</NavLink>}
          {canSeeKeys && <NavLink to="/events">Event log</NavLink>}
          <NavLink to="/team">Team</NavLink>
          {canSeeAudit && <NavLink to="/audit">Audit log</NavLink>}
          <NavLink to="/settings">Settings</NavLink>
          {/* Not role-gated: everyone benefits from reading how the thing works, and the page
              degrades to placeholders for roles that may not see keys. */}
          <NavLink to="/docs">Docs</NavLink>
        </nav>
        <div className="who">
          <div><strong>{me.merchant.name}</strong></div>
          <div>{me.user.email}</div>
          <div className="muted">{me.role}</div>
          <button className="ghost small" style={{ marginTop: 10 }} onClick={signOut}>
            Sign out
          </button>
          <div style={{ marginTop: 12 }}>
            <a href="/checkout" target="_blank" rel="noreferrer">Demo checkout ↗</a>
          </div>
        </div>
      </aside>

      <main className="main">
        <Routes>
          <Route path="/payments" element={<Payments />} />
          <Route path="/payments/:id" element={<PaymentDetail role={me.role} />} />
          <Route path="/api-keys" element={<ApiKeys role={me.role} />} />
          <Route path="/endpoints" element={<Endpoints role={me.role} />} />
          <Route path="/events" element={<Events />} />
          <Route path="/team" element={<Team role={me.role} meUserId={me.user.id} />} />
          <Route path="/audit" element={<AuditLog />} />
          <Route path="/settings" element={<Settings role={me.role} />} />
          <Route path="/docs" element={<Docs role={me.role} />} />
          <Route path="*" element={<Navigate to="/payments" replace />} />
        </Routes>
      </main>
    </div>
  );
}

export default function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [checked, setChecked] = useState(false);

  function refresh() {
    return api
      .get<Me>('/dashboard/me')
      .then(setMe)
      .catch(() => setMe(null))
      .finally(() => setChecked(true));
  }

  useEffect(() => {
    // A 401 here is the normal unauthenticated case, not an error worth showing.
    refresh();
  }, []);

  if (!checked) return <Spinner label="Checking your session…" />;

  return (
    <BrowserRouter>
      {me ? (
        <Shell me={me} onSignedOut={() => setMe(null)} />
      ) : (
        <Routes>
          <Route path="/login" element={<Login onAuthenticated={refresh} />} />
          <Route path="/signup" element={<Signup onAuthenticated={refresh} />} />
          <Route path="/forgot-password" element={<ForgotPassword />} />
          <Route path="/reset-password" element={<ResetPassword />} />
          <Route path="/accept-invitation" element={<AcceptInvitation onAuthenticated={refresh} />} />
          {/* Anything else while signed out lands on the login screen, including a deep link
              the user followed from an email — they can navigate back after signing in. */}
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      )}
    </BrowserRouter>
  );
}

import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from './api';

/** Money arrives in minor units. Rendering it as a float anywhere would be a bug. */
export function money(minor: number, currency: string): string {
  const zeroDecimal = ['JPY', 'KRW', 'VND', 'CLP', 'XAF', 'XOF'];
  if (zeroDecimal.includes(currency?.toUpperCase())) return `${currency} ${minor}`;
  const sign = minor < 0 ? '-' : '';
  const abs = Math.abs(minor);
  return `${sign}${currency} ${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

export function when(epochSeconds?: number): string {
  if (!epochSeconds) return '—';
  return new Date(epochSeconds * 1000).toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

export function Status({ value }: { value: string }) {
  const tone =
    value === 'succeeded' || value === 'delivered' || value === 'won'
      ? 'ok'
      : value === 'failed' || value === 'canceled' || value === 'dead' || value === 'lost'
        ? 'bad'
        : value === 'requires_action' || value === 'requires_capture' || value === 'retrying'
          ? 'warn'
          : 'muted';
  return <span className={`pill ${tone}`}>{value.replace(/_/g, ' ')}</span>;
}

export function Banner({ kind, children }: { kind: 'ok' | 'bad' | 'info'; children: ReactNode }) {
  return <div className={`banner ${kind}`}>{children}</div>;
}

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return <div className="muted pad">{label}</div>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/**
 * Fetch-on-mount with the three states every screen needs.
 *
 * Returning `reload` matters: almost every page mutates something and then has to show the result,
 * and refetching is more honest than patching local state, which drifts from the server.
 */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    let live = true;
    setLoading(true);
    loader()
      .then((result) => {
        if (live) {
          setData(result);
          setError(null);
        }
      })
      .catch((e) => live && setError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => live && setLoading(false));
    return () => {
      // Guards against a slow response landing after the user has navigated away.
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, nonce]);

  return { data, error, loading, reload: () => setNonce((n) => n + 1) };
}

/** Shows a secret exactly once, with copy — because there is no second chance to read it. */
export function RevealOnce({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="reveal">
      <div className="reveal-label">{label}</div>
      <code className="reveal-value">{value}</code>
      <button
        className="ghost"
        onClick={() => {
          navigator.clipboard?.writeText(value);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        }}
      >
        {copied ? 'Copied' : 'Copy'}
      </button>
    </div>
  );
}

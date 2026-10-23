import { useId } from 'react';
import { useStore } from '../app/runtime.js';
import { formatInt } from '../lib/format.js';
import { PanelState } from './Panel.jsx';

const UNITS_HELP = 'price tick × quantity; cash can go negative (contract §1)';

/** The selected trader's balances, then every account. Amounts are simulation units. */
export default function AccountPanel({ traderId, onTrader }) {
  const accounts = useStore((s) => s.accounts);
  const error = useStore((s) => s.errors.accounts);
  const helpId = useId();
  if (!accounts) {
    return error ? <PanelState kind="error">Accounts unavailable: {error}</PanelState> : <PanelState kind="loading">Loading accounts…</PanelState>;
  }
  if (accounts.length === 0) {
    return <PanelState kind="empty">No accounts. Start the backend with --seed-demo-accounts, or POST /api/accounts.</PanelState>;
  }
  const a = accounts.find((x) => String(x.traderId) === String(traderId));
  return (
    <div>
      <p className="units">
        Amounts in <abbr title={UNITS_HELP} aria-describedby={helpId}>simulation units</abbr>
        <span id={helpId} className="sr-only">{UNITS_HELP}</span>
      </p>
      {a ? (
        <dl className="kv kv-account">
          <dt>Trader</dt><dd className="num">T{a.traderId}</dd>
          <dt>Cash</dt><dd className={`num${a.cash < 0 ? ' tone-ask' : ''}`}>{formatInt(a.cash)}</dd>
          <dt>Margin limit</dt><dd className="num">{formatInt(a.marginLimit)}</dd>
        </dl>
      ) : null}
      <div className="scroll scroll-short">
        <table className="mini-table">
          <caption className="sr-only">All accounts</caption>
          <thead><tr><th scope="col">Trader</th><th scope="col" className="num">Cash</th><th scope="col" className="num">Margin limit</th></tr></thead>
          <tbody>
            {accounts.map((x) => (
              <tr key={x.traderId} className={String(x.traderId) === String(traderId) ? 'selected' : undefined}>
                <td><button type="button" className="link num" onClick={() => onTrader(String(x.traderId))}>T{x.traderId}</button></td>
                <td className="num">{formatInt(x.cash)}</td>
                <td className="num">{formatInt(x.marginLimit)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

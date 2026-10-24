import { useStore } from '../app/runtime.js';
import { SCREENS, href, useScreen } from './route.js';
import { splitBalance, usePracticeAccount } from './practice.js';
import MarketsScreen from './MarketsScreen.jsx';

function Mark() {
  return (
    <svg className="mark" width="32" height="32" viewBox="0 0 32 32" aria-hidden="true">
      <circle cx="16" cy="16" r="16" fill="var(--lime)" />
      <path d="M9 12h11m0 0-3-3m3 3-3 3M23 20H12m0 0 3-3m-3 3 3 3" fill="none" stroke="var(--ink)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function PracticeChip() {
  const { balance } = usePracticeAccount();
  const bal = balance === null ? null : splitBalance(balance);
  return (
    <span className="practice-chip">
      <span className="practice-dot" aria-hidden="true" />
      <span className="practice-label">Practice</span>
      <span className="practice-amount num">{bal ? `${bal.whole}${bal.cents}` : '—'}</span>
    </span>
  );
}

function ComingNext({ title }) {
  return (
    <div className="screen">
      <section className="card list-card">
        <h1 className="display">{title}</h1>
        <p className="empty">This screen is being built next.</p>
      </section>
    </div>
  );
}

const VIEWS = {
  markets: MarketsScreen,
  trade: () => <ComingNext title="Trade" />,
  portfolio: () => <ComingNext title="Portfolio" />,
  opportunities: () => <ComingNext title="Opportunities" />,
};

export default function ConsumerApp() {
  const screen = useScreen();
  const down = useStore((s) => s.health.up === false);
  const paused = useStore((s) => {
    const st = s.status;
    return s.health.up !== false && st && st.lastTickAt == null && Object.keys(s.rates).length === 0;
  });
  const View = VIEWS[screen];
  return (
    <div className="app">
      <header className="topbar">
        <a className="brand display" href={href('markets')} aria-label="Cambio, home"><Mark /><span>Cambio</span></a>
        <nav className="nav" aria-label="Main">
          {SCREENS.map((s) => (
            <a key={s.id} href={href(s.id)} className={screen === s.id ? 'nav-pill nav-active' : 'nav-pill'} aria-current={screen === s.id ? 'page' : undefined}>
              {s.label}
            </a>
          ))}
        </nav>
        <PracticeChip />
      </header>
      {down ? <p className="notice" role="status">Can’t reach Cambio right now. Trying again…</p> : null}
      {paused ? <p className="notice" role="status">Prices paused. The market may be closed.</p> : null}
      <main className="page" id="main"><View /></main>
      <footer className="footer">Practice account. Simulated money. Nothing here is financial advice.</footer>
    </div>
  );
}

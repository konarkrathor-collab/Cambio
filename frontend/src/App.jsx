import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import TopBar from './components/TopBar.jsx';
import MarketView from './components/MarketView.jsx';
import ArbitrageView from './components/ArbitrageView.jsx';
import SimulatorView from './components/SimulatorView.jsx';
import InspectorView from './components/InspectorView.jsx';
import DemoDrawer from './components/DemoDrawer.jsx';
import { useStore } from './app/runtime.js';
import ViewBoundary from './components/ViewBoundary.jsx';

const TABS = [
  { id: 'market', label: 'Market', View: MarketView },
  { id: 'arbitrage', label: 'Arbitrage', View: ArbitrageView },
  { id: 'simulator', label: 'Simulator', View: SimulatorView },
  { id: 'inspector', label: 'Inspector', View: InspectorView },
];

const INTRO_KEY = 'ads-intro-seen';
const INTRO_MS = 1300;

/** The staged intro (DESIGN.md §6): first load of a browser tab only, never under reduced motion. */
function wantsIntro() {
  try {
    if (window.sessionStorage.getItem(INTRO_KEY)) return false;
  } catch {
    return false;
  }
  return !(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
}

/** True while the intro plays; any key, click or wheel input ends it at once. */
function useIntro() {
  const [intro, setIntro] = useState(wantsIntro);
  useEffect(() => {
    if (!intro) return undefined;
    try {
      window.sessionStorage.setItem(INTRO_KEY, '1');
    } catch {
      // storage unavailable: the intro simply plays again next time
    }
    const end = () => setIntro(false);
    const t = setTimeout(end, INTRO_MS);
    const opts = { once: true, capture: true };
    window.addEventListener('keydown', end, opts);
    window.addEventListener('pointerdown', end, opts);
    window.addEventListener('wheel', end, opts);
    return () => {
      clearTimeout(t);
      window.removeEventListener('keydown', end, opts);
      window.removeEventListener('pointerdown', end, opts);
      window.removeEventListener('wheel', end, opts);
    };
  }, [intro]);
  return intro;
}

export default function App() {
  const [tab, setTab] = useState('market');
  const refs = useRef({});
  const navRef = useRef(null);
  const inkRef = useRef(null);
  const intro = useIntro();

  // WAI-ARIA tabs: arrow keys move between tabs, Home/End jump
  const onKeyDown = (e) => {
    const i = TABS.findIndex((t) => t.id === tab);
    let next = null;
    if (e.key === 'ArrowRight') next = (i + 1) % TABS.length;
    else if (e.key === 'ArrowLeft') next = (i - 1 + TABS.length) % TABS.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = TABS.length - 1;
    if (next === null) return;
    e.preventDefault();
    setTab(TABS[next].id);
    refs.current[TABS[next].id]?.focus();
  };

  // the amber underline slides to the active tab (and follows font loading and resizes)
  useLayoutEffect(() => {
    const place = () => {
      const el = refs.current[tab];
      if (!el || !inkRef.current) return;
      inkRef.current.style.width = `${el.offsetWidth}px`;
      inkRef.current.style.transform = `translateX(${el.offsetLeft}px)`;
    };
    place();
    let ro = null;
    if (typeof ResizeObserver !== 'undefined' && navRef.current) {
      ro = new ResizeObserver(place);
      ro.observe(navRef.current);
    }
    document.fonts?.ready?.then(place);
    return () => ro?.disconnect();
  }, [tab]);

  // stagger the intro: each panel and top-bar item gets its index
  useLayoutEffect(() => {
    if (!intro) return;
    for (const sel of ['.view .panel', '.topbar-items > *']) {
      document.querySelectorAll(sel).forEach((el, i) => el.style.setProperty('--i', String(i)));
    }
  }, [intro, tab]);

  const backendDown = useStore((s) => s.health.up === false);
  const { View, label } = TABS.find((t) => t.id === tab);
  const tabs = (
    <nav className="tabs" role="tablist" aria-label="Views" onKeyDown={onKeyDown} ref={navRef}>
      {TABS.map((t) => (
        <button
          key={t.id}
          ref={(el) => {
            refs.current[t.id] = el;
          }}
          id={`tab-${t.id}`}
          role="tab"
          type="button"
          aria-selected={tab === t.id}
          aria-controls={`panel-${t.id}`}
          tabIndex={tab === t.id ? 0 : -1}
          className="tab"
          onClick={() => setTab(t.id)}
        >
          {t.label}
        </button>
      ))}
      <span className="tab-ink" ref={inkRef} aria-hidden="true" />
    </nav>
  );
  return (
    <>
    <p className="narrow-notice" role="note">
      This dashboard needs a window at least 1024 pixels wide. Widen the window or zoom out.
    </p>
    <div className={intro ? 'app intro' : 'app'}>
      <TopBar tabs={tabs} />
      {backendDown ? (
        <div className="outage" role="alert">
          The backend is not reachable. Panels show the last data received; reconnecting automatically.
        </div>
      ) : null}
      <main id={`panel-${tab}`} role="tabpanel" aria-labelledby={`tab-${tab}`} className="view">
        <ViewBoundary name={label} resetKey={tab}>
          <View />
        </ViewBoundary>
      </main>
      <ViewBoundary name="Demo tools" resetKey="demo">
        <DemoDrawer />
      </ViewBoundary>
    </div>
    </>
  );
}

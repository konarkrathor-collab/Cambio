import { useRef, useState } from 'react';
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

export default function App() {
  const [tab, setTab] = useState('market');
  const refs = useRef({});

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

  const backendDown = useStore((s) => s.health.up === false);
  const { View, label } = TABS.find((t) => t.id === tab);
  return (
    <>
    <p className="narrow-notice" role="note">
      This dashboard needs a window at least 1024 pixels wide. Widen the window or zoom out.
    </p>
    <div className="app">
      <TopBar />
      {backendDown ? (
        <div className="outage" role="alert">
          The backend is not reachable. Panels show the last data received; reconnecting automatically.
        </div>
      ) : null}
      <nav className="tabs" role="tablist" aria-label="Views" onKeyDown={onKeyDown}>
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
      </nav>
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

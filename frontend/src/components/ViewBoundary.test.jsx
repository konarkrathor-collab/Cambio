// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ViewBoundary from './ViewBoundary.jsx';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

let broken = true;
function Fragile() {
  if (broken) throw new Error('boom in the arbitrage view');
  return <p>graph drawn</p>;
}

describe('per-view error boundary', () => {
  let container;
  let root;
  let errorSpy;
  beforeEach(() => {
    broken = true;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {}); // React reports caught errors
  });
  afterEach(() => {
    act(() => root.unmount());
    container.remove();
    errorSpy.mockRestore();
  });

  it('shows a recoverable message in the failing view and leaves the others working', () => {
    act(() => {
      root.render(
        <div>
          <ViewBoundary name="Market" resetKey="market"><p>market ok</p></ViewBoundary>
          <ViewBoundary name="Arbitrage" resetKey="arbitrage"><Fragile /></ViewBoundary>
        </div>,
      );
    });
    expect(container.textContent).toContain('market ok');
    const alert = container.querySelector('[role=alert]');
    expect(alert.textContent).toContain('Arbitrage view failed');
    expect(alert.textContent).toContain('boom in the arbitrage view');

    broken = false;
    act(() => alert.querySelector('button').click());
    expect(container.querySelector('[role=alert]')).toBeNull();
    expect(container.textContent).toContain('graph drawn');
    expect(container.textContent).toContain('market ok');
  });

  it('clears the error when the user switches to another view', () => {
    const view = (key, child) => <ViewBoundary name="X" resetKey={key}>{child}</ViewBoundary>;
    act(() => root.render(view('a', <Fragile />)));
    expect(container.querySelector('[role=alert]')).not.toBeNull();
    broken = false;
    act(() => root.render(view('b', <p>other view</p>)));
    expect(container.textContent).toContain('other view');
  });
});

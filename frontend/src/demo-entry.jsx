// The terminal-style dashboard, kept at #/demo for the faculty talk (Data structure inspector,
// simulator, demo drawer). Loaded only on that route so its global styles never touch Cambio.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './styles.css';

export default function mountDemo() {
  document.querySelector('meta[name="color-scheme"]')?.setAttribute('content', 'dark');
  document.title = 'Order Matching & Arbitrage Detector';
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import ConsumerApp from './ConsumerApp.jsx';
import { startHistory } from './history.js';
import './consumer.css';

export default function mountConsumer() {
  document.querySelector('meta[name="color-scheme"]')?.setAttribute('content', 'light');
  document.title = 'Cambio';
  startHistory();
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <ConsumerApp />
    </StrictMode>,
  );
}

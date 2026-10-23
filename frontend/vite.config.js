import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const BACKEND = 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: BACKEND,
        changeOrigin: false,
        // The event stream must reach the browser as it is written: no buffering, no
        // compression, no proxy timeout on a connection that stays open.
        timeout: 0,
        proxyTimeout: 0,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, req, res) => {
            if (!String(proxyRes.headers['content-type'] || '').startsWith('text/event-stream')) return;
            proxyRes.headers['cache-control'] = 'no-cache, no-transform';
            proxyRes.headers['x-accel-buffering'] = 'no';
            // When the backend goes away mid-stream the proxy would keep the browser's side
            // open forever, and EventSource would never notice. Close it so it reconnects.
            proxyRes.on('close', () => {
              if (!res.writableEnded) res.destroy();
            });
          });
        },
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.js'],
  },
});

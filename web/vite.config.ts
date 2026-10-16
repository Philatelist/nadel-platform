import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // In dev, API requests go through the proxy: no CORS, same origin as in production behind Caddy
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});

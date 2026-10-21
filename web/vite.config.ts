import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'eslint-rules/**/*.test.js'],
  },
  server: {
    port: 5173,
    // In dev, API requests go through the proxy: no CORS, same origin as in production behind Caddy
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});

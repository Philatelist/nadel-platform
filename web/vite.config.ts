import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { helpPlugin } from './help/vitePlugin.ts';

export default defineConfig({
  plugins: [react(), tailwindcss(), helpPlugin()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'help/**/*.test.ts', 'eslint-rules/**/*.test.js'],
  },
  server: {
    port: 5173,
    // In dev, API requests go through the proxy: no CORS, same origin as in production behind Caddy
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});

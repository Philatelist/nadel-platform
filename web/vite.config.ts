import tailwindcss from '@tailwindcss/vite';
import { palette } from '@nadel/ui-tokens';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import { defineConfig } from 'vitest/config';
import { helpPlugin } from './help/vitePlugin.ts';

// The same API proxy serves `vite dev` and `vite preview` (the PWA e2e runs the production build on the preview server).
const apiProxy = { '/api': 'http://localhost:8080' };

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    helpPlugin(),
    VitePWA({
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      registerType: 'prompt',
      // registerPwa.ts registers the worker (production builds only); the plugin injects nothing into index.html scripts.
      injectRegister: false,
      manifest: {
        id: '/',
        name: 'Наділ',
        short_name: 'Наділ',
        lang: 'uk',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        background_color: palette.light.background,
        theme_color: palette.light.background,
        icons: [
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      injectManifest: {
        // Build output, fonts, icons and the help pages with their images (docs/help is public by design).
        globPatterns: ['**/*.{js,css,html,svg,woff2,png,webmanifest}', 'help/**/*'],
      },
      devOptions: { enabled: false },
    }),
  ],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'help/**/*.test.ts', 'eslint-rules/**/*.test.js'],
  },
  define: {
    __BUILD_ID__: JSON.stringify(process.env.NADEL_BUILD_ID ?? 'dev'),
  },
  server: {
    port: 5173,
    // In dev, API requests go through the proxy: no CORS, same origin as in production behind Caddy
    proxy: apiProxy,
  },
  preview: {
    port: 5173,
    strictPort: true,
    proxy: apiProxy,
  },
});

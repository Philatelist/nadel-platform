// Generates the app icons once (not a build step): the favicon glyph on the accent colour, rendered with Playwright.
// Run: pnpm --filter web exec node scripts/make-icons.mjs   (needs `make e2e-install`). The PNGs are committed in public/.
import { chromium } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const ACCENT = '#245A41'; // palette.light.accent in packages/ui-tokens
const out = (name) => fileURLToPath(new URL(`../public/${name}`, import.meta.url));

// The four-plot glyph of favicon.svg (white/green/amber/red squares); `scale` shrinks it for the maskable safe zone.
function svg(size, { rounded, scale }) {
  const glyph = 32 * scale;
  const offset = (32 - glyph) / 2;
  const radius = rounded ? 7 : 0;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 32 32">
  <rect width="32" height="32" rx="${radius}" fill="${ACCENT}"/>
  <g transform="translate(${offset} ${offset}) scale(${scale})">
    <rect x="6" y="6" width="9" height="9" rx="1.5" fill="#2E8B57"/>
    <rect x="17" y="6" width="9" height="9" rx="1.5" fill="#D49B12"/>
    <rect x="6" y="17" width="9" height="9" rx="1.5" fill="#FFFFFF"/>
    <rect x="17" y="17" width="9" height="9" rx="1.5" fill="#CF4339"/>
  </g></svg>`;
}

const icons = [
  { name: 'icon-192.png', size: 192, rounded: false, scale: 1 },
  { name: 'icon-512.png', size: 512, rounded: false, scale: 1 },
  // maskable: the launcher crops to a shape; keep the glyph inside the central 80 % safe zone
  { name: 'icon-maskable-512.png', size: 512, rounded: false, scale: 0.7 },
  // iOS rounds the corners itself
  { name: 'apple-touch-icon.png', size: 180, rounded: false, scale: 1 },
];

const browser = await chromium.launch();
try {
  for (const { name, size, rounded, scale } of icons) {
    const page = await browser.newPage({ viewport: { width: size, height: size } });
    await page.setContent(`<body style="margin:0">${svg(size, { rounded, scale })}</body>`);
    await writeFile(out(name), await page.screenshot({ type: 'png' }));
    await page.close();
    console.log(`wrote public/${name}`);
  }
} finally {
  await browser.close();
}

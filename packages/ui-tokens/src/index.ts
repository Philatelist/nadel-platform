/**
 * Nadel design tokens shared by web (CSS variables) and mobile (StyleSheet).
 * Plot status colors are semantic and are never used as the UI accent.
 */
export const palette = {
  light: {
    background: '#F2F5F1',
    surface: '#FFFFFF',
    ink: '#16211B',
    muted: '#5A6A60',
    line: '#D3DCD5',
    accent: '#2347B5',
    accentSoft: '#E2E8FA',
    onAccent: '#FFFFFF',
  },
  dark: {
    background: '#0F1512',
    surface: '#161F1A',
    ink: '#E5ECE7',
    muted: '#93A39A',
    line: '#2B3731',
    accent: '#86A0FF',
    accentSoft: '#1C2645',
    onAccent: '#0F1512',
  },
} as const;

export const plotStatusColors = {
  light: { RED: '#CF4339', YELLOW: '#D49B12', GREEN: '#2E8B57', GREY: '#B9C3BD' },
  dark: { RED: '#EE6B60', YELLOW: '#E6B83C', GREEN: '#4DBA7C', GREY: '#46524C' },
} as const;

export const spacing = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;
export const radius = { sm: 6, md: 10, lg: 14, pill: 999 } as const;

export const fonts = {
  display: 'Unbounded',
  body: 'Golos Text',
  mono: 'JetBrains Mono',
} as const;

export type ThemeName = keyof typeof palette;
export type PlotStatusKey = keyof (typeof plotStatusColors)['light'];

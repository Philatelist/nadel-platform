/**
 * Nadel design tokens shared by web (CSS variables) and mobile (StyleSheet).
 * Plot status colors are semantic and are never used as the UI accent.
 */
export const palette = {
  light: {
    background: '#F3F5F0',
    surface: '#FFFFFF',
    line: '#D9DFD4',
    lineSubtle: '#EDF0EA',
    lineStrong: '#B9C3B3',
    ink: '#1C2A22',
    inkMuted: '#46564C',
    inkSubtle: '#5B6A60',
    accent: '#245A41',
    accentHover: '#143826',
    accentSoft: '#E3EEE6',
    onAccent: '#FFFFFF',
    danger: '#A3301B',
    dangerSoft: '#FBE9E5',
    warningSoft: '#FFF6E5',
    warningLine: '#EBCB98',
    warningInk: '#6B4A0B',
    successSoft: '#DCEFE3',
    successInk: '#14532D',
    infoSoft: '#E1E9F6',
    infoInk: '#1E3A6E',
  },
  dark: {
    background: '#0F1512',
    surface: '#17211B',
    line: '#2B3A31',
    lineSubtle: '#1F2B24',
    lineStrong: '#41554A',
    ink: '#E6EDE8',
    inkMuted: '#B4C2B8',
    inkSubtle: '#94A59A',
    accent: '#7FCB9C',
    accentHover: '#A3DDB9',
    accentSoft: '#1D3326',
    onAccent: '#0F1512',
    danger: '#F08A78',
    dangerSoft: '#3A1D18',
    warningSoft: '#33290F',
    warningLine: '#6B5420',
    warningInk: '#F2CF8A',
    successSoft: '#17301F',
    successInk: '#8FD6A8',
    infoSoft: '#1B2A44',
    infoInk: '#A9C4F0',
  },
} as const;

export const plotStatusColors = {
  light: { GREEN: '#2E8B57', YELLOW: '#E0B23A', RED: '#C2412B', GREY: '#9AA39A' },
  dark: { GREEN: '#4DBA7C', YELLOW: '#E6B83C', RED: '#E5604F', GREY: '#6F7C74' },
} as const;

/** Text color drawn on top of each plot status color. */
export const plotStatusOnColor = {
  light: { GREEN: '#FFFFFF', YELLOW: '#1C2A22', RED: '#FFFFFF', GREY: '#1C2A22' },
  dark: { GREEN: '#0F1512', YELLOW: '#0F1512', RED: '#0F1512', GREY: '#0F1512' },
} as const;

export const typography = {
  family: {
    serif: 'IBM Plex Serif',
    sans: 'IBM Plex Sans',
    mono: 'IBM Plex Mono',
  },
  size: { h1: 30, h2: 17, body: 15, bodyMember: 16, label: 14, hint: 13 },
  weight: { regular: 400, medium: 500, semibold: 600 },
} as const;

export const spacing = { 1: 4, 2: 8, 3: 12, 4: 16, 5: 20, 6: 24, 7: 28, 8: 32, 12: 48 } as const;
export const radius = { control: 10, panel: 12, pill: 999 } as const;
export const layout = { contentMaxWidth: 1060, minTarget: 44, menuBreakpoint: 768 } as const;

export type ThemeName = keyof typeof palette;
export type PlotStatusKey = keyof (typeof plotStatusColors)['light'];

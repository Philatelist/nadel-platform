import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { applyTheme, readTheme, writeTheme, type ThemeChoice } from './theme';

interface ThemeValue {
  theme: ThemeChoice;
  setTheme: (theme: ThemeChoice) => void;
}

const ThemeContext = createContext<ThemeValue>({ theme: 'system', setTheme: () => {} });

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeChoice>(readTheme);
  const setTheme = useCallback((next: ThemeChoice) => {
    setThemeState(next);
    writeTheme(next);
    applyTheme(next);
  }, []);
  const value = useMemo(() => ({ theme, setTheme }), [theme, setTheme]);
  return <ThemeContext value={value}>{children}</ThemeContext>;
}

export function useTheme(): ThemeValue {
  return useContext(ThemeContext);
}

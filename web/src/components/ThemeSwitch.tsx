import { THEMES } from '../theme';
import { useTheme } from '../themeContext';
import { useT } from '../i18n';

export function ThemeSwitch() {
  const { theme, setTheme } = useTheme();
  const t = useT();
  return (
    <div className="flex gap-1" role="group" aria-label={t.nav.theme}>
      {THEMES.map((choice) => (
        <button
          key={choice}
          type="button"
          aria-pressed={choice === theme}
          onClick={() => setTheme(choice)}
          className={`inline-flex min-h-11 min-w-11 items-center justify-center rounded-control px-2 text-xs ${choice === theme ? 'bg-accent-soft text-accent' : 'text-ink-muted hover:text-ink'}`}
        >
          {t.theme[choice]}
        </button>
      ))}
    </div>
  );
}

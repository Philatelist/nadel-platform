import { LOCALES } from '@nadel/shared';
import { useLocale, useT } from '../i18n';

export function LocaleSwitch() {
  const { locale, setLocale } = useLocale();
  const t = useT();
  return (
    <div className="flex gap-1" role="group" aria-label={t.nav.language}>
      {LOCALES.map((code) => (
        <button
          key={code}
          type="button"
          aria-pressed={code === locale}
          onClick={() => setLocale(code)}
          className={`inline-flex min-h-11 min-w-11 items-center justify-center rounded-md px-2 text-xs uppercase ${code === locale ? 'bg-accent-soft text-accent' : 'text-ink-muted hover:text-ink'}`}
        >
          {code}
        </button>
      ))}
    </div>
  );
}

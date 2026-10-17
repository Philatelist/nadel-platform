import { useT } from '../i18n';

export function QueryError({ onRetry }: { onRetry: () => void }) {
  const t = useT();
  return (
    <div className="flex flex-col items-start gap-3 p-6">
      <p className="text-ink-muted">{t.errors.generic}</p>
      <button type="button" onClick={onRetry} className="rounded-md border border-line px-3 py-1.5 text-sm">
        {t.errors.retry}
      </button>
    </div>
  );
}

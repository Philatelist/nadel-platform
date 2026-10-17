import { useT } from '../i18n';

export function NotFoundPage() {
  const t = useT();
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <h1 className="font-display text-2xl font-bold">{t.access.notFound}</h1>
    </div>
  );
}

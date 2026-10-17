import { useT } from '../i18n';

export function NoAccess() {
  const t = useT();
  return (
    <div role="alert" className="mx-auto max-w-2xl px-4 py-8">
      <h1 className="font-serif text-2xl font-bold">{t.access.denied}</h1>
    </div>
  );
}

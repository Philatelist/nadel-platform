import { useGetMe } from '@nadel/api-client';
import { QueryError } from '../components/QueryState';
import { useT } from '../i18n';

export function CabinetPage() {
  const t = useT();
  const { data, isLoading, isError, refetch } = useGetMe();

  if (isLoading) return <div className="p-6 text-muted">…</div>;
  if (isError || !data) return <QueryError onRetry={() => void refetch()} />;
  const me = data;

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6 px-4 py-8">
      <h1 className="font-display text-2xl font-bold">{t.me.title}</h1>
      <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-3 rounded-xl border border-line bg-surface p-5">
        <dt className="text-muted">Email</dt>
        <dd>{me.email}</dd>
        <dt className="text-muted">{t.me.cooperative}</dt>
        <dd>{me.tenant?.name ?? t.me.noCooperative}</dd>
        <dt className="text-muted">{t.me.roles}</dt>
        <dd className="flex flex-wrap gap-1.5">
          {me.roles.map((role) => (
            <span key={role} className="rounded-full bg-accent-soft px-2.5 py-0.5 text-sm">
              {t.roles[role]}
            </span>
          ))}
        </dd>
      </dl>
      <p className="text-sm text-muted">{me.fullName}</p>
    </div>
  );
}

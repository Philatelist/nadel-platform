import { useGetMember, type Permission } from '@nadel/api-client';
import { useLocale, useT } from '../i18n';
import { QueryBoundary } from '../components/QueryBoundary';
import { MemberDetails } from '../components/MemberDetails';
import { Badge, PageHeader, Panel } from '../components/ui';
import { useMe } from '../me';
import { useRoleName } from '../roleName';

export function AccountPage() {
  const t = useT();
  const { locale } = useLocale();
  const me = useMe();
  const roleName = useRoleName();
  // memberId is always present for a non-operator; the operator never reaches this page
  const member = useGetMember(me.memberId ?? '', { query: { enabled: me.memberId !== undefined } });
  const permissions: readonly Permission[] = me.permissions;

  return (
    <div className="flex flex-col gap-4 text-base" lang={locale}>
      <PageHeader title={t.account.title} />

      <div className="grid grid-cols-1 items-start gap-4 md:grid-cols-2">
        <section className="flex min-w-0 flex-col gap-3">
          <QueryBoundary query={member}>
            {(data) => <MemberDetails member={data} title={t.account.details} large />}
          </QueryBoundary>
        </section>

        <Panel title={t.account.roles}>
          <ul className="flex flex-wrap gap-2">
            {me.roles.map((role) => (
              <li key={role.id}>
                <Badge tone="success">{roleName(role)}</Badge>
              </li>
            ))}
          </ul>
          <h2 className="text-[17px] font-semibold text-ink">{t.account.cooperative}</h2>
          <p>{me.tenant?.name}</p>
          <h2 className="text-[17px] font-semibold text-ink">{t.account.permissions}</h2>
          {permissions.length === 0 ? (
            <p className="text-ink-muted">{t.account.noPermissions}</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {permissions.map((p) => (
                <li key={p} className="rounded-control border border-line bg-surface px-4 py-2">
                  <div className="font-medium">{t.permission[p].name}</div>
                  <div className="text-sm text-ink-muted">{t.permission[p].description}</div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </div>
  );
}

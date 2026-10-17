import { useGetMember, type Permission } from '@nadel/api-client';
import { useLocale, useT } from '../i18n';
import { QueryBoundary } from '../components/QueryBoundary';
import { MemberDetails } from '../components/MemberDetails';
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
    <div className="mx-auto flex max-w-2xl flex-col gap-6 px-4 py-8" lang={locale}>
      <h1 className="font-serif text-2xl font-bold">{t.account.title}</h1>

      <section className="flex flex-col gap-3">
        <h2 className="font-semibold">{t.account.details}</h2>
        <QueryBoundary query={member}>{(data) => <MemberDetails member={data} />}</QueryBoundary>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="font-semibold">{t.account.cooperative}</h2>
        <p>{me.tenant?.name}</p>
        <h2 className="font-semibold">{t.account.roles}</h2>
        <ul className="flex flex-wrap gap-1.5">
          {me.roles.map((role) => (
            <li key={role.id} className="rounded-full bg-accent-soft px-2.5 py-0.5 text-sm">
              {roleName(role)}
            </li>
          ))}
        </ul>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="font-semibold">{t.account.permissions}</h2>
        {permissions.length === 0 ? (
          <p className="text-ink-muted">{t.account.noPermissions}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {permissions.map((p) => (
              <li key={p} className="rounded-lg border border-line bg-surface px-4 py-2">
                <div className="font-medium">{t.permission[p].name}</div>
                <div className="text-sm text-ink-muted">{t.permission[p].description}</div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

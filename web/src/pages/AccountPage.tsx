import { useGetMember, type Member, type Permission, type RoleRef } from '@nadel/api-client';
import { useLocale, useT } from '../i18n';
import { QueryBoundary } from '../components/QueryBoundary';
import { useMe } from '../me';

function useRoleName(): (role: RoleRef) => string {
  const t = useT();
  return (role) => (role.baseCode ? t.roles[role.baseCode] : role.name);
}

function MemberCard({ member }: { member: Member }) {
  const t = useT();
  const f = t.member.fields;
  const roleName = useRoleName();
  const fullName = [member.lastName, member.firstName, member.middleName].filter(Boolean).join(' ');
  const rows: [string, string][] = [
    [f.name, fullName],
    [f.phones, member.phones.join(', ') || f.none],
    [f.email, member.email ?? f.none],
    [f.postalAddress, member.postalAddress ?? f.none],
    [f.deliveryChannel, t.channel[member.deliveryChannel]],
  ];
  if (member.telegramUsername) rows.push([f.telegramUsername, member.telegramUsername]);
  if (member.viberPhone) rows.push([f.viberPhone, member.viberPhone]);
  if (member.notes) rows.push([f.notes, member.notes]);
  rows.push([f.roles, member.roles.map(roleName).join(', ') || f.none]);

  return (
    <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-3 rounded-xl border border-line bg-surface p-5">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-muted">{label}</dt>
          <dd className="break-words whitespace-pre-line">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

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
      <h1 className="font-display text-2xl font-bold">{t.account.title}</h1>

      <section className="flex flex-col gap-3">
        <h2 className="font-semibold">{t.account.details}</h2>
        <QueryBoundary query={member}>{(data) => <MemberCard member={data} />}</QueryBoundary>
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
          <p className="text-muted">{t.account.noPermissions}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {permissions.map((p) => (
              <li key={p} className="rounded-lg border border-line bg-surface px-4 py-2">
                <div className="font-medium">{t.permission[p].name}</div>
                <div className="text-sm text-muted">{t.permission[p].description}</div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

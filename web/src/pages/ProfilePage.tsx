import { useGetMember, type Permission } from '@nadel/api-client';
import { Link } from 'react-router';
import { useLocale, useT } from '../i18n';
import { QueryBoundary } from '../components/QueryBoundary';
import { NotificationsPanel } from '../components/account/NotificationsPanel';
import { HowToInstallLink } from '../components/account/HowToInstallLink';
import { MemberDetails } from '../components/MemberDetails';
import { Badge, PageHeader, Panel } from '../components/ui';
import { RoleName } from '../components/RoleName';
import { UserContent } from '../components/UserContent';
import { isBoardUser } from '../help/helpVisibility';
import { useMe } from '../me';
import { useDocumentTitle } from '../useDocumentTitle';
import { useScrollToHash } from '../hooks/useScrollToHash';

/** «My details»: personal data, the invoice channel, Telegram, push devices and, for the board, roles and permissions. */
export function ProfilePage() {
  const t = useT();
  useDocumentTitle(t.account.details);
  useScrollToHash();
  const { locale } = useLocale();
  const me = useMe();
  // memberId is always present for a non-operator; the operator never reaches this page
  const member = useGetMember(me.memberId ?? '', { query: { enabled: me.memberId !== undefined } });
  const permissions: readonly Permission[] = me.permissions;

  return (
    <div className="flex flex-col gap-4 text-base" lang={locale}>
      <Link to="/account" className="inline-flex min-h-11 items-center text-ink-muted hover:text-ink">
        {t.account.backToAccount}
      </Link>
      <PageHeader title={t.account.details} />

      <div className="grid grid-cols-1 items-start gap-4 md:grid-cols-2">
        <section className="flex min-w-0 flex-col gap-3">
          <QueryBoundary query={member}>{(data) => <MemberDetails member={data} large />}</QueryBoundary>
        </section>

        {isBoardUser(permissions) && (
          <Panel title={t.account.roles}>
            <ul className="flex flex-wrap gap-2">
              {me.roles.map((role) => (
                <li key={role.id}>
                  <Badge tone="success">
                    <RoleName role={role} />
                  </Badge>
                </li>
              ))}
            </ul>
            <h2 className="text-[17px] font-semibold text-ink">{t.account.cooperative}</h2>
            <p>{me.tenant?.name && <UserContent>{me.tenant.name}</UserContent>}</p>
            <h2 className="text-[17px] font-semibold text-ink">{t.account.permissions}</h2>
            <ul className="flex flex-col gap-2">
              {permissions.map((p) => (
                <li key={p} className="rounded-control border border-line bg-surface px-4 py-2">
                  <div className="font-medium">{t.permission[p].name}</div>
                  <div className="text-sm text-ink-muted">{t.permission[p].description}</div>
                </li>
              ))}
            </ul>
          </Panel>
        )}
      </div>

      <NotificationsPanel />
      <div>
        <HowToInstallLink />
      </div>
    </div>
  );
}

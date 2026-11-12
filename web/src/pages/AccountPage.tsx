import { Link } from 'react-router';
import { useLocale, useT } from '../i18n';
import { PageHeader } from '../components/ui';
import { MyPlotsPanel } from '../components/ownership/MyPlotsPanel';
import { MyAnnouncementsPanel } from '../components/announcements/MyAnnouncementsPanel';
import { MyMetersPanel } from '../components/readings/MyMetersPanel';
import { MyInvoices } from '../components/invoices/MyInvoices';
import { MyChargesPanel } from '../components/charges/MyChargesPanel';
import { MyExpectedChargesPanel } from '../components/tariffs/MyExpectedChargesPanel';
import { InstallCard } from '../pwa/InstallCard';
import { useDocumentTitle } from '../useDocumentTitle';
import { useScrollToHash } from '../hooks/useScrollToHash';

export function AccountPage() {
  const t = useT();
  useDocumentTitle(t.account.title);
  useScrollToHash();
  const { locale } = useLocale();

  return (
    <div className="flex flex-col gap-4 text-base" lang={locale}>
      <PageHeader title={t.account.title} />

      <InstallCard />

      {/* What the member uses first: what is owed, then meters. Personal data lives on «My details». */}
      <Link
        to="/account/profile"
        className="inline-flex min-h-11 items-center self-start text-[15px] text-accent underline"
      >
        {t.account.details}
      </Link>

      <MyChargesPanel />
      <MyInvoices />
      <MyExpectedChargesPanel />
      <MyMetersPanel />
      <MyAnnouncementsPanel />
      <MyPlotsPanel />
    </div>
  );
}

import type { Permission } from '@nadel/api-client';
import { NavLink } from 'react-router';
import { useT } from '../../i18n';
import { useMe } from '../../me';

interface Tab {
  to: string;
  label: 'months' | 'notCharged' | 'losses' | 'common' | 'recalculations';
  /** Omitted: every holder of CHARGES_VIEW sees the tab. */
  permission?: Permission;
  end?: boolean;
}

/** Each tab may sit behind its permission; without one, every holder of CHARGES_VIEW sees it. */
const TABS: readonly Tab[] = [
  { to: '/charges', label: 'months' },
  { to: '/charges/not-charged', label: 'notCharged' },
  { to: '/charges/losses', label: 'losses' },
  { to: '/charges/common', label: 'common' },
  { to: '/charges/recalculations', label: 'recalculations' },
];

const tabClass = ({ isActive }: { isActive: boolean }) =>
  `flex min-h-11 items-center border-b-2 px-3 text-[15px] no-underline ${
    isActive ? 'border-accent font-semibold text-accent' : 'border-transparent text-ink hover:text-accent'
  }`;

export function ChargesTabs() {
  const t = useT();
  const me = useMe();
  const tabs = TABS.filter((tab) => !tab.permission || me.permissions.includes(tab.permission));
  return (
    <nav aria-label={t.billing.tabsLabel} className="flex flex-wrap gap-1 border-b border-line">
      {tabs.map((tab) => (
        <NavLink key={tab.to} to={tab.to} end className={tabClass}>
          {t.billing.tabs[tab.label]}
        </NavLink>
      ))}
    </nav>
  );
}

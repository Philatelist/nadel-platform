import type { Ownership, PlotOwnerships } from '@nadel/api-client';
import { format, formatShare } from '@nadel/shared';
import { Link } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { useCan } from '../../me';

/** Date-only values ("2015-06-01") are calendar days, so they are formatted in UTC to avoid a shifted day. */
function formatDay(value: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeZone: 'UTC' }).format(new Date(value));
}

function OwnerName({ ownership }: { ownership: Ownership }) {
  const t = useT();
  const canOpenMember = useCan('MEMBERS_VIEW');
  return (
    <>
      {canOpenMember ? (
        <Link to={`/members/${ownership.memberId}`} className="font-medium text-accent underline">
          {ownership.memberName}
        </Link>
      ) : (
        <span className="font-medium">{ownership.memberName}</span>
      )}
      {ownership.memberArchived && (
        <span className="ml-2 text-sm text-ink-muted">({t.ownership.archivedMember})</span>
      )}
    </>
  );
}

function OwnershipRow({ ownership, kind }: { ownership: Ownership; kind: 'current' | 'history' }) {
  const t = useT();
  const { locale } = useLocale();
  const when =
    kind === 'current' || ownership.validTo === undefined
      ? format(t.ownership.since, { date: formatDay(ownership.validFrom, locale) })
      : format(t.ownership.period, {
          from: formatDay(ownership.validFrom, locale),
          to: formatDay(ownership.validTo, locale),
        });
  return (
    <li className="flex flex-col gap-0.5 border-b border-line pb-2 last:border-b-0 last:pb-0">
      <div className="flex flex-wrap items-baseline gap-x-3">
        <OwnerName ownership={ownership} />
        <span>{formatShare(ownership.share)}</span>
        <span className="text-ink-muted">{when}</span>
        {/* Slice 3-4: row actions (Correct) go here for people who may edit ownership. */}
      </div>
      {ownership.basis && (
        <div className="text-sm text-ink-muted">{format(t.ownership.basis, { basis: ownership.basis })}</div>
      )}
    </li>
  );
}

/** Read-only current owners and history (newest first, as the server orders them) of one plot. */
export function OwnershipLists({ ownerships }: { ownerships: PlotOwnerships }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-5">
      <section aria-labelledby="ownership-current" className="flex flex-col gap-2">
        <h3 id="ownership-current" className="text-[15px] font-semibold">
          {t.ownership.current}
        </h3>
        {ownerships.current.length === 0 ? (
          <p className="text-ink-muted">{t.ownership.noOwner}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {ownerships.current.map((o) => (
              <OwnershipRow key={o.id} ownership={o} kind="current" />
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="ownership-history" className="flex flex-col gap-2">
        <h3 id="ownership-history" className="text-[15px] font-semibold">
          {t.ownership.history}
        </h3>
        {ownerships.history.length === 0 ? (
          <p className="text-ink-muted">{t.ownership.noHistory}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {ownerships.history.map((o) => (
              <OwnershipRow key={o.id} ownership={o} kind="history" />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

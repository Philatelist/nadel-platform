import { useGetAnnouncement } from '@nadel/api-client';
import { format, formatDateTime } from '@nadel/shared';
import { keepPreviousData } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { DeliveryTable } from '../components/deliveries/DeliveryTable';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import { PageHeader, Panel } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

const PAGE_SIZE = 50;

/** One announcement: its text as entered, audience, counts and the deliveries. */
export function AnnouncementPage() {
  const t = useT();
  const { locale } = useLocale();
  const a = t.announcements;
  useDocumentTitle(a.title);
  const { id = '' } = useParams();
  const [page, setPage] = useState(0);
  const query = useGetAnnouncement(
    id,
    { page, size: PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  );
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <QueryBoundary query={query}>
        {(data) => (
          <>
            <PageHeader
              title={a.title}
              actions={
                <Link to="/announcements" className="text-accent underline">
                  {a.detail.back}
                </Link>
              }
            />
            <Panel>
              <dl className="grid gap-3 sm:grid-cols-[10rem_1fr]">
                <dt className="text-ink-muted">{a.detail.text}</dt>
                <dd>
                  <p data-user-content className="whitespace-pre-wrap break-words">
                    {data.text}
                  </p>
                </dd>
                <dt className="text-ink-muted">{a.columns.date}</dt>
                <dd>{formatDateTime(data.createdAt, locale)}</dd>
                <dt className="text-ink-muted">{a.columns.author}</dt>
                <dd>{data.authorName === undefined ? '—' : <UserContent>{data.authorName}</UserContent>}</dd>
                <dt className="text-ink-muted">{a.detail.audience}</dt>
                <dd>
                  {data.streets.length === 0 ? (
                    a.allMembers
                  ) : (
                    <UserContent>{data.streets.join(', ')}</UserContent>
                  )}
                </dd>
                <dt className="text-ink-muted">{a.detail.channels}</dt>
                <dd>{data.channels.map((c) => t.channel[c]).join(', ')}</dd>
                <dt className="text-ink-muted">{a.detail.counts}</dt>
                <dd>{format(a.counts, { ...data.counts })}</dd>
              </dl>
            </Panel>
            <Panel>
              <div className="flex flex-col gap-4">
                <h2 className="text-lg font-semibold">{a.detail.deliveries}</h2>
                <DeliveryTable rows={data.deliveries.items} />
                <Pagination
                  page={data.deliveries.page}
                  totalPages={data.deliveries.totalPages}
                  onPageChange={setPage}
                />
              </div>
            </Panel>
          </>
        )}
      </QueryBoundary>
    </div>
  );
}

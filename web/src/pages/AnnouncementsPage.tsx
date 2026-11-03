import { useListAnnouncements } from '@nadel/api-client';
import { format, formatDateTime } from '@nadel/shared';
import { keepPreviousData } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { Pagination } from '../components/Pagination';
import { QueryBoundary } from '../components/QueryBoundary';
import { UserContent } from '../components/UserContent';
import { buttonClass, DataTable, PageHeader, Panel } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { useDocumentTitle } from '../useDocumentTitle';

const PAGE_SIZE = 25;

/** The announcements of the board, newest first. */
export function AnnouncementsPage() {
  const t = useT();
  const { locale } = useLocale();
  const a = t.announcements;
  useDocumentTitle(a.title);
  const [page, setPage] = useState(0);
  const list = useListAnnouncements(
    { page, size: PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  );
  return (
    <div className="flex flex-col gap-4 text-[15px]">
      <PageHeader
        title={a.title}
        actions={
          <Link to="/announcements/new" className={buttonClass('primary')}>
            {a.newAnnouncement}
          </Link>
        }
      />
      <Panel>
        <div className="flex flex-col gap-4">
          <QueryBoundary query={list} isEmpty={(d) => d.items.length === 0} emptyMessage={a.empty}>
            {(data) => (
              <>
                <DataTable
                  caption={a.title}
                  emptyMessage={a.empty}
                  rowKey={(r) => r.id}
                  rows={data.items}
                  columns={[
                    {
                      key: 'date',
                      header: a.columns.date,
                      cell: (r) => (
                        <Link className="text-accent underline" to={`/announcements/${r.id}`}>
                          {formatDateTime(r.createdAt, locale)}
                        </Link>
                      ),
                    },
                    {
                      key: 'author',
                      header: a.columns.author,
                      cell: (r) =>
                        r.authorName === undefined ? '—' : <UserContent>{r.authorName}</UserContent>,
                    },
                    {
                      key: 'audience',
                      header: a.columns.audience,
                      cell: (r) =>
                        r.streets.length === 0 ? (
                          a.allMembers
                        ) : (
                          <UserContent>{r.streets.join(', ')}</UserContent>
                        ),
                    },
                    {
                      key: 'channels',
                      header: a.columns.channels,
                      cell: (r) => r.channels.map((c) => t.channel[c]).join(', '),
                    },
                    {
                      key: 'counts',
                      header: a.columns.counts,
                      cell: (r) => format(a.counts, { ...r.counts }),
                    },
                  ]}
                />
                <Pagination page={data.page} totalPages={data.totalPages} onPageChange={setPage} />
              </>
            )}
          </QueryBoundary>
        </div>
      </Panel>
    </div>
  );
}

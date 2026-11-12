import {
  getListMyAnnouncementsQueryKey,
  useListMyAnnouncements,
  useMarkMyAnnouncementRead,
  type MyAnnouncement,
} from '@nadel/api-client';
import { formatDateTime } from '@nadel/shared';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useLocation } from 'react-router';
import { useLocale, useT } from '../../i18n';
import { QueryBoundary } from '../QueryBoundary';
import { UserContent } from '../UserContent';
import { Badge, Panel } from '../ui';

/** The id the push link `/account#announcement-<id>` points at. */
export const announcementDomId = (id: string): string => `announcement-${id}`;

/**
 * «Announcements» on the account: what the board sent the member, newest first. Every item is shown in full; an unread
 * one carries «New» until the member taps it or arrives through its push link, which marks it read.
 */
export function MyAnnouncementsPanel() {
  const t = useT();
  const query = useListMyAnnouncements();
  const queryClient = useQueryClient();
  const { hash } = useLocation();
  const mark = useMarkMyAnnouncementRead({
    mutation: {
      onSuccess: () => queryClient.invalidateQueries({ queryKey: getListMyAnnouncementsQueryKey() }),
    },
  });

  const linked = hash.startsWith('#announcement-') ? hash.slice('#announcement-'.length) : undefined;
  const items = query.data;
  useEffect(() => {
    if (linked === undefined || !items) return;
    if (items.some((item) => item.id === linked && item.unread)) mark.mutate({ id: linked });
    // mark.mutate is stable enough for one-shot use per (link, list) pair
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linked, items]);

  return (
    <Panel title={t.announcements.title}>
      <QueryBoundary
        query={query}
        isEmpty={(data) => data.length === 0}
        emptyMessage={t.announcements.my.empty}
      >
        {(data) => (
          <ul className="flex flex-col gap-3">
            {data.map((item) => (
              <AnnouncementItem key={item.id} item={item} onOpen={() => mark.mutate({ id: item.id })} />
            ))}
          </ul>
        )}
      </QueryBoundary>
    </Panel>
  );
}

function AnnouncementItem({ item, onOpen }: { item: MyAnnouncement; onOpen: () => void }) {
  const t = useT();
  const { locale } = useLocale();
  return (
    <li
      id={announcementDomId(item.id)}
      // an unread item is reachable by Tab too: reaching it with the keyboard counts as opening it, like a tap
      tabIndex={item.unread ? 0 : undefined}
      onClick={item.unread ? onOpen : undefined}
      onFocus={item.unread ? onOpen : undefined}
      className="flex scroll-mt-4 flex-col gap-1 rounded-control border border-line bg-surface px-4 py-3"
    >
      <div className="flex flex-wrap items-center gap-2 text-sm text-ink-muted">
        <time dateTime={item.sentAt}>{formatDateTime(item.sentAt, locale)}</time>
        {item.unread && <Badge tone="info">{t.announcements.my.new}</Badge>}
      </div>
      <p className="whitespace-pre-line break-words">
        <UserContent>{item.text}</UserContent>
      </p>
    </li>
  );
}

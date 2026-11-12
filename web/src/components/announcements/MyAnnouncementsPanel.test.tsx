import { screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { MyAnnouncement } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { MyAnnouncementsPanel } from './MyAnnouncementsPanel';

const mark = vi.fn();
let items: MyAnnouncement[];

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListMyAnnouncements: () => ({
    data: items,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useMarkMyAnnouncementRead: () => ({ mutate: mark }),
}));

const unread: MyAnnouncement = {
  id: 'a1',
  sentAt: '2026-11-05T09:00:00Z',
  text: 'Збори в суботу\nО 10:00',
  unread: true,
};
const read: MyAnnouncement = {
  id: 'a2',
  sentAt: '2026-10-01T09:00:00Z',
  text: 'Вода відключена',
  unread: false,
};

const wrap = () => (
  <QueryClientProvider client={new QueryClient()}>
    <MyAnnouncementsPanel />
  </QueryClientProvider>
);

describe('MyAnnouncementsPanel', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    mark.mockReset();
    items = [unread, read];
  });

  it('shows the full text with an id per item and «Нове» only on the unread one', () => {
    renderWithApp(wrap());
    const first = document.getElementById('announcement-a1')!;
    expect(first).toHaveTextContent('Збори в суботу');
    expect(first).toHaveTextContent('О 10:00');
    expect(first).toHaveTextContent('Нове');
    expect(document.getElementById('announcement-a2')).not.toHaveTextContent('Нове');
  });

  it('marks an unread item read when it is tapped, and ignores a read one', async () => {
    const user = userEvent.setup();
    renderWithApp(wrap());
    await user.click(document.getElementById('announcement-a2')!);
    expect(mark).not.toHaveBeenCalled();
    await user.click(document.getElementById('announcement-a1')!);
    expect(mark).toHaveBeenCalledWith({ id: 'a1' });
  });

  it('marks the announcement named by the URL hash as read', async () => {
    renderWithApp(wrap(), { route: '/account#announcement-a1' });
    await waitFor(() => expect(mark).toHaveBeenCalledWith({ id: 'a1' }));
  });

  it('shows an empty state', () => {
    items = [];
    renderWithApp(wrap());
    expect(screen.getByText('Від правління поки немає оголошень.')).toBeInTheDocument();
  });
});

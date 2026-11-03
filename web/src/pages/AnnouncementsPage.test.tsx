import { screen } from '@testing-library/react';
import type { AnnouncementDetails, AnnouncementPage as Page } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { AnnouncementPage } from './AnnouncementPage';
import { AnnouncementsPage } from './AnnouncementsPage';

let list: Page;
let detail: AnnouncementDetails;

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListAnnouncements: () => ({
    data: list,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useGetAnnouncement: () => ({
    data: detail,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

const counts = { sent: 4, failed: 1, queued: 2, paper: 3 };
const countsText = 'Надіслано: 4, помилок: 1, у черзі: 2, на папері: 3';

beforeEach(() => {
  window.localStorage.setItem('nadel.locale', 'uk');
  list = {
    items: [
      {
        id: 'a1',
        createdAt: '2026-10-29T09:00:00Z',
        authorName: 'Іваненко Іван',
        streets: [],
        channels: ['TELEGRAM', 'EMAIL'],
        counts,
      },
      {
        id: 'a2',
        createdAt: '2026-10-28T09:00:00Z',
        streets: ['Садова', 'Вишнева'],
        channels: ['EMAIL'],
        counts,
      },
    ],
    page: 0,
    size: 25,
    totalItems: 2,
    totalPages: 1,
  };
  detail = {
    id: 'a1',
    createdAt: '2026-10-29T09:00:00Z',
    authorName: 'Іваненко Іван',
    text: 'Перший рядок\nДругий рядок',
    streets: [],
    channels: ['EMAIL'],
    counts,
    deliveries: {
      items: [
        {
          id: 'd1',
          kind: 'ANNOUNCEMENT',
          plotNumber: '9',
          memberName: 'Петренко Петро',
          channel: 'EMAIL',
          status: 'SENT',
          attempts: 1,
          announcementId: 'a1',
        },
      ],
      page: 0,
      size: 50,
      totalItems: 1,
      totalPages: 1,
      summary: [],
    },
  } as AnnouncementDetails;
});

describe('AnnouncementsPage', () => {
  it('lists audience, channels and counts and offers a new announcement', () => {
    renderWithApp(<AnnouncementsPage />);
    expect(screen.getByText('Усі члени')).toBeInTheDocument();
    expect(screen.getByText('Садова, Вишнева')).toBeInTheDocument();
    expect(screen.getByText('Telegram, Електронна пошта')).toBeInTheDocument();
    expect(screen.getAllByText(countsText)).toHaveLength(2);
    expect(screen.getByRole('link', { name: 'Нове оголошення' })).toHaveAttribute(
      'href',
      '/announcements/new',
    );
  });
});

describe('AnnouncementPage', () => {
  it('shows the text with line breaks preserved and the deliveries', () => {
    renderWithApp(<AnnouncementPage />, { route: '/announcements/a1' });
    const text = screen.getByText(/Перший рядок/);
    expect(text.className).toContain('whitespace-pre-wrap');
    expect(text.textContent).toBe('Перший рядок\nДругий рядок');
    expect(screen.getByText('Петренко Петро')).toBeInTheDocument();
    expect(screen.getByText(countsText)).toBeInTheDocument();
  });
});

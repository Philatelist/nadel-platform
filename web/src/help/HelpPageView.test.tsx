import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HelpBundle } from '../../help/types';
import { renderWithApp } from '../test/render';
import { HelpPageView } from './HelpPageView';
import { HelpViewerProvider } from './helpViewer';
import type { HelpViewer } from './helpVisibility';

let bundle: HelpBundle;

vi.mock('./useHelpBundle', () => ({
  useHelpBundle: () => ({
    data: bundle,
    error: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

const everyone: HelpViewer = { kind: 'user', permissions: [], ownsPlot: false };

function render(route: string, viewer: HelpViewer = everyone) {
  return renderWithApp(
    <HelpViewerProvider viewer={viewer}>
      <Routes>
        <Route path="/help/:id" element={<HelpPageView />} />
      </Routes>
    </HelpViewerProvider>,
    { route },
  );
}

beforeEach(() => {
  bundle = {
    locale: 'uk',
    search: null,
    pages: [
      {
        id: 'sample',
        locale: 'uk',
        title: 'Тестова сторінка',
        description: 'Опис.',
        kind: 'screen',
        routes: [],
        permissions: [],
        section: 'getting-started',
        order: 1,
        shots: [],
        headings: [
          { level: 2, id: 'one', text: 'Перша частина' },
          { level: 3, id: 'two', text: 'Друга частина' },
        ],
        body: [
          { type: 'h2', id: 'one', children: [{ type: 'text', text: 'Перша частина' }] },
          { type: 'p', children: [{ type: 'text', text: 'Текст сторінки.' }] },
          { type: 'callout', children: [{ type: 'p', children: [{ type: 'text', text: 'Обережно.' }] }] },
          { type: 'img', shot: 'form', alt: 'Форма' },
          { type: 'h3', id: 'two', children: [{ type: 'text', text: 'Друга частина' }] },
        ],
      },
    ],
  };
});

describe('HelpPageView', () => {
  it('renders the page with a sticky list of its headings', () => {
    render('/help/sample');
    expect(screen.getByRole('heading', { level: 1, name: 'Тестова сторінка' })).toBeInTheDocument();
    expect(screen.getByText('Текст сторінки.')).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'На цій сторінці' });
    expect(nav.querySelectorAll('a')).toHaveLength(2);
    expect(nav.querySelector('a')).toHaveAttribute('href', '#one');
  });

  it('shows the important callout with its catalog title and the screenshot from the static folder', () => {
    render('/help/sample');
    expect(screen.getByText('Важливо')).toBeInTheDocument();
    expect(screen.getByText('Обережно.')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Форма' })).toHaveAttribute('src', '/help/img/uk/form.png');
  });

  it('shows the not-found page for an unknown id', () => {
    render('/help/missing');
    expect(screen.getByRole('heading', { name: 'Не знайдено' })).toBeInTheDocument();
    expect(screen.queryByText('Тестова сторінка')).toBeNull();
  });

  it('shows only the unavailable notice and a link to the contents for a hidden page', () => {
    bundle.pages.push({
      ...bundle.pages[0]!,
      id: 'secret',
      title: 'Секретна сторінка',
      permissions: ['AUDIT_VIEW'],
      body: [{ type: 'p', children: [{ type: 'text', text: 'Закритий текст.' }] }],
    });
    render('/help/secret');
    expect(screen.getByText('Цей розділ вам недоступний')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'До змісту довідки' })).toHaveAttribute('href', '/help');
    expect(screen.queryByText('Секретна сторінка')).toBeNull();
    expect(screen.queryByText('Закритий текст.')).toBeNull();
  });

  it('shows a hidden page to an anonymous visitor', () => {
    bundle.pages[0]!.permissions = ['AUDIT_VIEW'];
    render('/help/sample', { kind: 'anonymous' });
    expect(screen.getByRole('heading', { level: 1, name: 'Тестова сторінка' })).toBeInTheDocument();
  });

  it('renders a link to a hidden page as plain text and a link to a visible page as a link', () => {
    bundle.pages.push({
      ...bundle.pages[0]!,
      id: 'secret',
      title: 'Секретна сторінка',
      permissions: ['AUDIT_VIEW'],
    });
    bundle.pages[0]!.body = [
      {
        type: 'p',
        children: [
          { type: 'link', pageId: 'secret', children: [{ type: 'text', text: 'закрита' }] },
          { type: 'link', pageId: 'sample', children: [{ type: 'text', text: 'відкрита' }] },
        ],
      },
    ];
    render('/help/sample');
    expect(screen.getByText('закрита')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'закрита' })).toBeNull();
    expect(screen.getByRole('link', { name: 'відкрита' })).toHaveAttribute('href', '/help/sample');
  });
});

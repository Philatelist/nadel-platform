import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HelpBundle } from '../../help/types';
import { renderWithApp } from '../test/render';
import { HelpPageView } from './HelpPageView';

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

function render(route: string) {
  return renderWithApp(
    <Routes>
      <Route path="/help/:id" element={<HelpPageView />} />
    </Routes>,
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
});

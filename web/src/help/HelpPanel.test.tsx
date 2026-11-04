import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HelpBundle } from '../../help/types';
import { renderWithApp } from '../test/render';
import { HelpButton } from './HelpButton';
import { HelpViewerProvider } from './helpViewer';
import type { HelpViewer } from './helpVisibility';

let bundle: HelpBundle;

vi.mock('./useHelpBundle', () => ({
  useHelpBundle: () => ({ data: bundle, error: null, isPending: false, isError: false, refetch: vi.fn() }),
}));

const treasurer: HelpViewer = { kind: 'user', permissions: ['PAYMENTS_VIEW'], ownsPlot: false };

function render(route: string, viewer: HelpViewer = treasurer) {
  return renderWithApp(
    <HelpViewerProvider viewer={viewer}>
      <HelpButton />
      <Routes>
        <Route path="*" element={<p>Екран за панеллю</p>} />
      </Routes>
    </HelpViewerProvider>,
    { route },
  );
}

function page(id: string, title: string, routes: string[], permissions: string[]) {
  return {
    id,
    locale: 'uk' as const,
    title,
    description: '',
    kind: 'screen' as const,
    routes,
    permissions,
    section: 'payments-cash' as const,
    order: 1,
    shots: [],
    headings: [],
    body: [{ type: 'p' as const, children: [{ type: 'text' as const, text: `Текст: ${title}` }] }],
  };
}

beforeEach(() => {
  bundle = {
    locale: 'uk',
    search: null,
    pages: [
      page('payments', 'Платежі', ['/payments'], ['PAYMENTS_VIEW']),
      page('audit', 'Журнал дій', ['/audit'], ['AUDIT_VIEW']),
    ],
  };
});

beforeEach(() => window.localStorage.setItem('nadel.locale', 'uk'));

describe('HelpPanel', () => {
  it('opens the page of the current screen and keeps the screen behind it', async () => {
    render('/payments');
    await userEvent.click(screen.getByRole('button', { name: 'Довідка про цей екран' }));
    expect(screen.getByRole('dialog', { name: 'Платежі' })).toBeInTheDocument();
    expect(screen.getByText('Текст: Платежі')).toBeInTheDocument();
    expect(screen.getByText('Екран за панеллю')).toBeInTheDocument();
  });

  it('links to the full page', async () => {
    render('/payments');
    await userEvent.click(screen.getByRole('button', { name: 'Довідка про цей екран' }));
    expect(screen.getByRole('link', { name: 'Відкрити повністю' })).toHaveAttribute('href', '/help/payments');
  });

  it('closes with the close button and with Escape', async () => {
    render('/payments');
    const open = screen.getByRole('button', { name: 'Довідка про цей екран' });
    await userEvent.click(open);
    await userEvent.click(screen.getByRole('button', { name: 'Закрити' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(open);
    await userEvent.keyboard('{Escape}');
    // jsdom does not fire the native cancel event on Escape, so dispatch it as the browser would.
    screen.queryByRole('dialog')?.dispatchEvent(new Event('cancel', { cancelable: true }));
    expect(await screen.findByRole('button', { name: 'Довідка про цей екран' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('has no button on a screen without a visible page', () => {
    render('/audit');
    expect(screen.queryByRole('button', { name: 'Довідка про цей екран' })).toBeNull();
  });

  it('has no button on a screen no page covers', () => {
    render('/nowhere');
    expect(screen.queryByRole('button', { name: 'Довідка про цей екран' })).toBeNull();
  });
});

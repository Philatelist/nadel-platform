import { screen } from '@testing-library/react';
import type { Permission } from '@nadel/api-client';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { HomeRedirect } from './HomeRedirect';

let permissions: Permission[];
vi.mock('../me', () => ({ useCan: (p: Permission) => permissions.includes(p) }));

function renderHome() {
  renderWithApp(
    <Routes>
      <Route index element={<HomeRedirect />} />
      <Route path="plots" element={<p>plots page</p>} />
      <Route path="account" element={<p>account page</p>} />
    </Routes>,
  );
}

describe('HomeRedirect', () => {
  beforeEach(() => {
    permissions = [];
  });

  it('sends people who may view plots to the plots list', () => {
    permissions = ['PLOTS_VIEW'];
    renderHome();
    expect(screen.getByText('plots page')).toBeInTheDocument();
  });

  it('sends everyone else to their account', () => {
    renderHome();
    expect(screen.getByText('account page')).toBeInTheDocument();
  });
});

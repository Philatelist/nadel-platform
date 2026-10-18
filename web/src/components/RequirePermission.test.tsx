import { screen } from '@testing-library/react';
import type { Permission } from '@nadel/api-client';
import { Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { RequirePermission } from './RequirePermission';

let permissions: Permission[];

vi.mock('../me', () => ({
  useMe: () => ({ permissions }),
  useOptionalMe: () => null,
}));

function renderGuard() {
  renderWithApp(
    <Routes>
      <Route element={<RequirePermission permission={['PLOTS_VIEW', 'PLOTS_EDIT']} />}>
        <Route path="/plots/new" element={<div>form</div>} />
      </Route>
    </Routes>,
    { route: '/plots/new' },
  );
}

describe('RequirePermission with several permissions', () => {
  it('refuses PLOTS_EDIT alone', () => {
    permissions = ['PLOTS_EDIT'];
    renderGuard();
    expect(screen.queryByText('form')).toBeNull();
  });

  it('renders the route with both', () => {
    permissions = ['PLOTS_VIEW', 'PLOTS_EDIT'];
    renderGuard();
    expect(screen.getByText('form')).toBeInTheDocument();
  });
});

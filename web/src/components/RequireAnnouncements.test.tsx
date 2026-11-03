import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { Permission } from '@nadel/api-client';
import { renderWithApp } from '../test/render';
import { RequirePermission } from './RequirePermission';

let permissions: Permission[] = [];
vi.mock('../me', () => ({ useMe: () => ({ permissions }), useOptionalMe: () => null }));

function renderAt() {
  renderWithApp(
    <Routes>
      <Route element={<RequirePermission permission="ANNOUNCEMENTS_SEND" />}>
        <Route path="/announcements" element={<p>secret</p>} />
      </Route>
    </Routes>,
    { route: '/announcements' },
  );
}

describe('announcement pages guard', () => {
  it('hides the pages without ANNOUNCEMENTS_SEND', () => {
    permissions = ['PLOTS_VIEW'];
    renderAt();
    expect(screen.queryByText('secret')).not.toBeInTheDocument();
  });
  it('shows them with ANNOUNCEMENTS_SEND', () => {
    permissions = ['ANNOUNCEMENTS_SEND'];
    renderAt();
    expect(screen.getByText('secret')).toBeInTheDocument();
  });
});

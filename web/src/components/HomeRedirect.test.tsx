import { screen } from '@testing-library/react';
import type { Me, Permission } from '@nadel/api-client';
import { Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { HomeRedirect } from './HomeRedirect';

let permissions: Permission[];
let ownedPlots: Me['ownedPlots'];
vi.mock('../me', () => ({ useMe: () => ({ permissions, ownedPlots }) }));

function renderHome() {
  renderWithApp(
    <Routes>
      <Route index element={<HomeRedirect />} />
      <Route path="plots" element={<p>plots page</p>} />
      <Route path="readings" element={<p>readings page</p>} />
      <Route path="meters" element={<p>meters page</p>} />
      <Route path="account" element={<p>account page</p>} />
    </Routes>,
  );
}

describe('HomeRedirect', () => {
  beforeEach(() => {
    permissions = [];
    ownedPlots = [];
  });

  it('sends people who may view plots to the plots list', () => {
    permissions = ['PLOTS_VIEW', 'READINGS_ENTER'];
    renderHome();
    expect(screen.getByText('plots page')).toBeInTheDocument();
  });

  it('sends the electrician to the readings round', () => {
    permissions = ['READINGS_ENTER', 'METERS_VIEW'];
    renderHome();
    expect(screen.getByText('readings page')).toBeInTheDocument();
  });

  it('sends a meter viewer without the right to enter readings to the register', () => {
    permissions = ['METERS_VIEW'];
    renderHome();
    expect(screen.getByText('meters page')).toBeInTheDocument();
  });

  it('keeps an owner on their account even with meter permissions', () => {
    permissions = ['READINGS_ENTER'];
    ownedPlots = [{ plotId: 'p1', number: '7' }] as unknown as Me['ownedPlots'];
    renderHome();
    expect(screen.getByText('account page')).toBeInTheDocument();
  });

  it('sends everyone else to their account', () => {
    renderHome();
    expect(screen.getByText('account page')).toBeInTheDocument();
  });
});

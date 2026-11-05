import type { RouteObject } from 'react-router';
import { describe, expect, it } from 'vitest';
import { appRoutePaths } from './appRoutePaths';

describe('appRoutePaths', () => {
  it('walks nested routes into full patterns and skips *, index routes and the help routes', () => {
    const routes: RouteObject[] = [
      {
        children: [
          { index: true },
          { path: 'plots' },
          { path: 'plots/:id' },
          { children: [{ path: 'members' }, { path: 'members/new' }] },
          {
            path: 'charges',
            children: [
              { path: 'recalculations' },
              { path: 'recalculations/new', children: [{ index: true }] },
            ],
          },
          { path: 'help' },
          { path: 'help/:id' },
          { path: '*' },
        ],
      },
    ];
    expect(appRoutePaths(routes)).toEqual([
      '/plots',
      '/plots/:id',
      '/members',
      '/members/new',
      '/charges/recalculations',
      '/charges/recalculations/new',
    ]);
  });
});

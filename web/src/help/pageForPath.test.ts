import { describe, expect, it } from 'vitest';
import { pageForPath } from './pageForPath';

const pages = [
  { id: 'plots', routes: ['/plots'] },
  { id: 'plot-card', routes: ['/plots/:id'] },
  { id: 'plot-form', routes: ['/plots/new', '/plots/:id/edit'] },
  { id: 'charges-month', routes: ['/charges/months/:ym'] },
];

describe('pageForPath', () => {
  it('matches an exact route', () => {
    expect(pageForPath(pages, '/plots')?.id).toBe('plots');
  });

  it('matches a route with a param', () => {
    expect(pageForPath(pages, '/plots/42')?.id).toBe('plot-card');
    expect(pageForPath(pages, '/charges/months/2026-05')?.id).toBe('charges-month');
  });

  it('prefers the route with fewer params', () => {
    expect(pageForPath(pages, '/plots/new')?.id).toBe('plot-form');
  });

  it('matches a second route of the same page', () => {
    expect(pageForPath(pages, '/plots/42/edit')?.id).toBe('plot-form');
  });

  it('returns undefined when no page covers the path', () => {
    expect(pageForPath(pages, '/unknown')).toBeUndefined();
    expect(pageForPath(pages, '/plots/42/extra')).toBeUndefined();
  });
});

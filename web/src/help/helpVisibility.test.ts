import { describe, expect, it } from 'vitest';
import { ANONYMOUS_VIEWER, isBoardUser, isPageVisible, viewerFromMe, visiblePageIds } from './helpVisibility';

const pages = [
  { id: 'everyone', permissions: [] },
  { id: 'audit', permissions: ['AUDIT_VIEW'] },
  { id: 'readings', permissions: ['READINGS_ENTER'] },
  { id: 'meters', permissions: ['METERS_VIEW', 'METERS_MANAGE'] },
  { id: 'plots', permissions: ['PLOTS_VIEW', 'member'] },
  { id: 'my-account', permissions: ['member'] },
];

const plot = { id: 'p1', number: '1' } as never;

describe('helpVisibility', () => {
  it('shows the chairman every page that lists a permission he has, plus the pages for everyone', () => {
    const viewer = viewerFromMe({
      permissions: ['AUDIT_VIEW', 'READINGS_ENTER', 'METERS_VIEW', 'PLOTS_VIEW'],
      ownedPlots: [],
    });
    expect([...visiblePageIds(pages, viewer)]).toEqual(['everyone', 'audit', 'readings', 'meters', 'plots']);
  });

  it('shows a plain member the pages for everyone and the pages of an owner', () => {
    const viewer = viewerFromMe({ permissions: [], ownedPlots: [plot] });
    expect([...visiblePageIds(pages, viewer)]).toEqual(['everyone', 'plots', 'my-account']);
  });

  it('shows the chairman (MEMBERS_VIEW, no plot) the member pages too', () => {
    const viewer = viewerFromMe({ permissions: ['MEMBERS_VIEW'], ownedPlots: [] });
    expect([...visiblePageIds(pages, viewer)]).toEqual(['everyone', 'plots', 'my-account']);
  });

  it('hides the member pages from the electrician without a plot and without MEMBERS_VIEW', () => {
    const viewer = viewerFromMe({ permissions: ['READINGS_ENTER', 'METERS_VIEW'], ownedPlots: [] });
    expect([...visiblePageIds(pages, viewer)]).toEqual(['everyone', 'readings', 'meters']);
  });

  it('hides the owner pages from a member who owns no plot', () => {
    const viewer = viewerFromMe({ permissions: [], ownedPlots: [] });
    expect([...visiblePageIds(pages, viewer)]).toEqual(['everyone']);
  });

  it('shows the electrician (member plus meter permissions) the meter pages and, as an owner, the owner pages', () => {
    const viewer = viewerFromMe({ permissions: ['READINGS_ENTER', 'METERS_VIEW'], ownedPlots: [plot] });
    expect([...visiblePageIds(pages, viewer)]).toEqual([
      'everyone',
      'readings',
      'meters',
      'plots',
      'my-account',
    ]);
  });

  it('shows the platform operator (no permissions, no plots) only the pages for everyone', () => {
    const viewer = viewerFromMe({ permissions: [], ownedPlots: [] });
    expect(isPageVisible(pages[1]!, viewer)).toBe(false);
    expect(isPageVisible(pages[0]!, viewer)).toBe(true);
  });

  it('shows the anonymous visitor every page', () => {
    expect(visiblePageIds(pages, ANONYMOUS_VIEWER).size).toBe(pages.length);
  });

  it('shows a board page to anyone holding a permission, but not to a plain member or an owner', () => {
    const boardPages = [{ id: 'start', permissions: ['board'] }];
    const treasurer = viewerFromMe({ permissions: ['PAYMENTS_VIEW'], ownedPlots: [] });
    const member = viewerFromMe({ permissions: [], ownedPlots: [plot] });
    expect(isPageVisible(boardPages[0]!, treasurer)).toBe(true);
    expect(isPageVisible(boardPages[0]!, member)).toBe(false);
    expect(isPageVisible(boardPages[0]!, ANONYMOUS_VIEWER)).toBe(true);
    expect(isBoardUser([])).toBe(false);
    expect(isBoardUser(['AUDIT_VIEW'])).toBe(true);
  });
});

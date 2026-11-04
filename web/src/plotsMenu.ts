import type { Me } from '@nadel/api-client';

/**
 * "Plots" (the list of all plots) is for people who hold PLOTS_VIEW. Everyone else reaches their own plots from
 * "My account"; the server already limits a member to their own plots.
 */
export function showsPlots(me: Pick<Me, 'permissions'>): boolean {
  return me.permissions.includes('PLOTS_VIEW');
}

/** "My account" leads the menu of a person who has no plots list. */
export function showsAccount(me: Pick<Me, 'permissions'>): boolean {
  return !showsPlots(me);
}

import type { Me } from '@nadel/api-client';

/**
 * "Plots" (the list of all plots) is for people who hold PLOTS_VIEW. Everyone else reaches their own plots from
 * "My account"; the server already limits a member to their own plots.
 */
export function showsPlots(me: Pick<Me, 'permissions'>): boolean {
  return me.permissions.includes('PLOTS_VIEW');
}

/**
 * "My account" leads the menu of a person who owns a plot now and has no plots list. A person without a plot (the
 * electrician, a board member who owns nothing) does not get it; people with the plots list reach their account
 * through their name in the header.
 */
export function showsAccount(me: Pick<Me, 'permissions' | 'ownedPlots'>): boolean {
  return !showsPlots(me) && me.ownedPlots.length > 0;
}

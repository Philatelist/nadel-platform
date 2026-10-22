import type { Me } from '@nadel/api-client';

/**
 * "Plots" is for people who work with plots (PLOTS_VIEW) and for owners; a person who only reads meters
 * (READINGS_ENTER or METERS_VIEW) and has no plot of their own does not see it. Everyone else keeps it.
 */
export function showsPlots(me: Pick<Me, 'permissions' | 'ownedPlots'>): boolean {
  if (me.permissions.includes('PLOTS_VIEW') || me.ownedPlots.length > 0) return true;
  return !(me.permissions.includes('READINGS_ENTER') || me.permissions.includes('METERS_VIEW'));
}

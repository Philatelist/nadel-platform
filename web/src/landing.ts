import type { Me, Permission } from '@nadel/api-client';

type LandingMe = Pick<Me, 'permissions' | 'ownedPlots'>;

/**
 * The home screen of a signed-in person: plot people start on the plots list, a meter reader on the round or the
 * register, everyone else (including owners and plain members) on their account.
 */
export function landingPath({ permissions, ownedPlots }: LandingMe): string {
  const has = (permission: Permission): boolean => permissions.includes(permission);
  if (has('PLOTS_VIEW')) return '/plots';
  if (ownedPlots.length === 0 && has('READINGS_ENTER')) return '/readings';
  if (ownedPlots.length === 0 && has('METERS_VIEW')) return '/meters';
  return '/account';
}

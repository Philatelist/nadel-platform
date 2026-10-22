import { Navigate } from 'react-router';
import { useMe } from '../me';

/**
 * The landing route: plot people start on the plots list, a meter reader on the round or the register, everyone
 * else (including owners) on their account.
 */
export function HomeRedirect() {
  const { permissions, ownedPlots } = useMe();
  const has = (permission: (typeof permissions)[number]) => permissions.includes(permission);
  let landing = '/account';
  if (has('PLOTS_VIEW')) landing = '/plots';
  else if (ownedPlots.length === 0 && has('READINGS_ENTER')) landing = '/readings';
  else if (ownedPlots.length === 0 && has('METERS_VIEW')) landing = '/meters';
  return <Navigate to={landing} replace />;
}

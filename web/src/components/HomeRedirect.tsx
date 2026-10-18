import { Navigate } from 'react-router';
import { useCan } from '../me';

/** The landing route: people who may view plots start on the plots list, everyone else on their account. */
export function HomeRedirect() {
  const canViewPlots = useCan('PLOTS_VIEW');
  return <Navigate to={canViewPlots ? '/plots' : '/account'} replace />;
}

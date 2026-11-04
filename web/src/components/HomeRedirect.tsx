import { Navigate } from 'react-router';
import { landingPath } from '../landing';
import { useMe } from '../me';

/** The landing route: sends the person to their home screen (see `landingPath`). */
export function HomeRedirect() {
  return <Navigate to={landingPath(useMe())} replace />;
}

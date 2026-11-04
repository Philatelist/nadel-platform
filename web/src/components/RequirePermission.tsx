import type { Permission } from '@nadel/api-client';
import { Navigate, Outlet } from 'react-router';
import { useMe } from '../me';
import { NoAccess } from './NoAccess';

/**
 * Layout route: renders the child route, or `NoAccess` in place (same URL) without the permission.
 * With several permissions, all of them are required (or any one of them with `any`).
 */
export function RequirePermission({
  permission,
  any = false,
  redirectTo,
}: {
  permission: Permission | readonly Permission[];
  /** Any one of the permissions is enough, instead of all of them. */
  any?: boolean;
  /** Without the permission, go here instead of showing `NoAccess`. */
  redirectTo?: string;
}) {
  const { permissions } = useMe();
  const required = typeof permission === 'string' ? [permission] : permission;
  const allowed = any
    ? required.some((p) => permissions.includes(p))
    : required.every((p) => permissions.includes(p));
  if (allowed) return <Outlet />;
  return redirectTo ? <Navigate to={redirectTo} replace /> : <NoAccess />;
}

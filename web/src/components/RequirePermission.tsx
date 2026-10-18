import type { Permission } from '@nadel/api-client';
import { Outlet } from 'react-router';
import { useMe } from '../me';
import { NoAccess } from './NoAccess';

/**
 * Layout route: renders the child route, or `NoAccess` in place (same URL) without the permission.
 * With several permissions, all of them are required.
 */
export function RequirePermission({ permission }: { permission: Permission | readonly Permission[] }) {
  const { permissions } = useMe();
  const required = typeof permission === 'string' ? [permission] : permission;
  return required.every((p) => permissions.includes(p)) ? <Outlet /> : <NoAccess />;
}

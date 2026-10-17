import type { Permission } from '@nadel/api-client';
import { Outlet } from 'react-router';
import { useCan } from '../me';
import { NoAccess } from './NoAccess';

/** Layout route: renders the child route, or `NoAccess` in place (same URL) without the permission. */
export function RequirePermission({ permission }: { permission: Permission }) {
  return useCan(permission) ? <Outlet /> : <NoAccess />;
}

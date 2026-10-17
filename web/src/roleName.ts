import type { RoleRef } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';
import { useT } from './i18n';

/**
 * A base role that was not renamed is shown from the catalog (so it follows the UI language);
 * a renamed base role and every custom role show their stored name.
 */
export function roleDisplayName(
  role: Pick<RoleRef, 'name' | 'baseCode' | 'nameCustomized'>,
  t: Messages,
): string {
  return role.baseCode && !role.nameCustomized ? t.roles[role.baseCode] : role.name;
}

export function useRoleName(): (role: RoleRef) => string {
  const t = useT();
  return (role) => roleDisplayName(role, t);
}

/**
 * Audit values for a non-customised base-role name arrive as `base:<CODE>`; the `roles` field is a
 * ", "-joined list that may contain such tokens. Known codes are shown from the catalog.
 */
export function auditRoleValue(value: string, t: Messages): string {
  return value
    .split(', ')
    .map((part) => {
      if (!part.startsWith('base:')) return part;
      const code = part.slice('base:'.length);
      return Object.hasOwn(t.roles, code) ? t.roles[code as keyof Messages['roles']] : part;
    })
    .join(', ');
}

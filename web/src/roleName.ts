import type { RoleDetails, RoleRef } from '@nadel/api-client';
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

/** A base role with its standard description shows the catalog text; otherwise the stored one. */
export function roleDescription(
  role: Pick<RoleDetails, 'description' | 'baseCode' | 'descriptionCustomized'>,
  t: Messages,
): string | undefined {
  return role.baseCode && !role.descriptionCustomized ? t.roleDescriptions[role.baseCode] : role.description;
}

export function useRoleName(): (role: RoleRef) => string {
  const t = useT();
  return (role) => roleDisplayName(role, t);
}

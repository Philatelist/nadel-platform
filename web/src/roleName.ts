import type { RoleRef } from '@nadel/api-client';
import { useT } from './i18n';

/** Base roles are named from the catalog by `baseCode`; custom roles by their own name. */
export function useRoleName(): (role: RoleRef) => string {
  const t = useT();
  return (role) => (role.baseCode ? t.roles[role.baseCode] : role.name);
}

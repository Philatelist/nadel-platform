import type { RoleRef } from '@nadel/api-client';

/**
 * The name to send on save. A never-renamed base role whose field still holds its displayed
 * (catalog) name keeps its stored name, so a permission-only save does not rename the role.
 */
export function nameToSubmit(
  role: Pick<RoleRef, 'name' | 'baseCode' | 'nameCustomized'> | undefined,
  typed: string,
  display: string,
): string {
  const trimmed = typed.trim();
  const untouched =
    role !== undefined && role.baseCode !== undefined && !role.nameCustomized && trimmed === display.trim();
  return untouched ? role.name : trimmed;
}

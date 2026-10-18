import type { RoleDetails } from '@nadel/api-client';

/**
 * The description to send on save. A base role with its standard description whose field still holds
 * the displayed (catalog) text keeps its stored description, so a save does not mark it customised.
 */
export function descriptionToSubmit(
  role: Pick<RoleDetails, 'description' | 'baseCode' | 'descriptionCustomized'> | undefined,
  typed: string,
  display: string,
): string | undefined {
  const trimmed = typed.trim();
  const untouched =
    role !== undefined &&
    role.baseCode !== undefined &&
    !role.descriptionCustomized &&
    trimmed === display.trim();
  if (untouched) return role.description;
  return trimmed === '' ? undefined : trimmed;
}

import type { RoleDetails } from '@nadel/api-client';
import { descriptionToSubmit } from './roleDescriptionSubmit';

/** The description sent with "restore the standard name": the field text, unless it is the untouched standard text. */
export function restoreNameDescription(
  role: Pick<RoleDetails, 'description' | 'baseCode' | 'descriptionCustomized'>,
  typed: string,
  display: string,
): string | undefined {
  return descriptionToSubmit(role, typed, display);
}

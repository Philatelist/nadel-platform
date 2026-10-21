import type { ChargeType } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';

/**
 * The name to show for a charge type: the localised default while a starting type is not renamed
 * (the server stores the English default then), otherwise the stored name.
 */
export function chargeTypeName(
  type: Pick<ChargeType, 'code' | 'name' | 'nameCustomized'>,
  t: Messages,
): string {
  return !type.nameCustomized && type.code ? t.chargeTypes.defaultNames[type.code] : type.name;
}

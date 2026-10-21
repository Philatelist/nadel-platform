import type { ChargeType } from '@nadel/api-client';
import { useT } from '../i18n';
import { UserContent } from './UserContent';

type NamedChargeType = Pick<ChargeType, 'code' | 'name' | 'nameCustomized'>;

/** True when the name shown is the stored text (a custom or renamed type), not a catalog default. */
export function isChargeTypeNameUserContent(type: NamedChargeType): boolean {
  return Boolean(type.nameCustomized || !type.code);
}

/** A starting type that was never renamed shows the catalog name; any other name is user content. */
export function ChargeTypeName({ type }: { type: NamedChargeType }) {
  const t = useT();
  if (!isChargeTypeNameUserContent(type) && type.code) return <>{t.chargeTypes.defaultNames[type.code]}</>;
  return <UserContent>{type.name}</UserContent>;
}

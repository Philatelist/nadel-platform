import { useCan } from '../me';

/** Every meter write needs both permissions; the server answers 403 otherwise. */
export function useCanManageMeters(): boolean {
  const canView = useCan('METERS_VIEW');
  const canManage = useCan('METERS_MANAGE');
  return canView && canManage;
}

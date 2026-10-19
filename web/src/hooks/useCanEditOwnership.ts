import { useCan } from '../me';

/** Changing owners needs both permissions; the server answers 403 otherwise. */
export function useCanEditOwnership(): boolean {
  const canView = useCan('PLOTS_VIEW');
  const canEdit = useCan('OWNERSHIP_EDIT');
  return canView && canEdit;
}

import { useCan } from '../me';

/** Every plot write needs both permissions; the server answers 403 otherwise. */
export function useCanEditPlots(): boolean {
  const canView = useCan('PLOTS_VIEW');
  const canEdit = useCan('PLOTS_EDIT');
  return canView && canEdit;
}

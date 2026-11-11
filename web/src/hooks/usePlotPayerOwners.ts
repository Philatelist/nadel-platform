import { useListPlotOwnerships } from '@nadel/api-client';

export interface PlotPayerOwner {
  memberId: string;
  memberName: string;
}

/** The current, non-archived owners of a plot, and the id of the only one (`null` with none or several). */
export function usePlotPayerOwners(plotId: string | undefined): {
  owners: PlotPayerOwner[];
  soleOwnerId: string | null;
} {
  const ownerships = useListPlotOwnerships(plotId ?? '', { query: { enabled: plotId !== undefined } });
  const owners = (ownerships.data?.current ?? [])
    .filter((o) => !o.memberArchived)
    .map((o) => ({ memberId: o.memberId, memberName: o.memberName }));
  return { owners, soleOwnerId: owners.length === 1 ? owners[0]!.memberId : null };
}

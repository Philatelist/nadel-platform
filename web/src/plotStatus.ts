import type { PlotKind, PlotStatus } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';

/** GREY means "no owner" for a plot and "common land" for common land; other statuses have one label. */
export function plotStatusLabel(t: Messages, plot: { status: PlotStatus; kind: PlotKind }): string {
  if (plot.status === 'GREY') {
    return plot.kind === 'COMMON' ? t.status.GREY_COMMON : t.status.GREY_PLOT;
  }
  return t.status[plot.status];
}

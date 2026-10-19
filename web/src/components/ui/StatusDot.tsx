import type { PlotStatusKey } from '@nadel/ui-tokens';

const dots: Record<PlotStatusKey, string> = {
  GREEN: 'bg-status-green',
  YELLOW: 'bg-status-yellow',
  RED: 'bg-status-red',
  GREY: 'bg-status-grey',
};

/** Without a status (hidden from the viewer by the server) only a neutral, decorative ring is drawn. */
export function StatusDot({ status, label }: { status?: PlotStatusKey; label?: string }) {
  if (status === undefined || label === undefined) {
    return (
      <span
        aria-hidden="true"
        data-testid="status-neutral"
        className="inline-block size-3 shrink-0 rounded-full border-2 border-line-strong"
      />
    );
  }
  return (
    <span className="inline-flex items-center gap-2">
      <span aria-hidden="true" className={`size-3 shrink-0 rounded-full ${dots[status]}`} />
      <span>{label}</span>
    </span>
  );
}

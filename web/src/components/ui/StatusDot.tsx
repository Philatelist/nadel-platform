import type { PlotStatusKey } from '@nadel/ui-tokens';

const dots: Record<PlotStatusKey, string> = {
  GREEN: 'bg-status-green',
  YELLOW: 'bg-status-yellow',
  RED: 'bg-status-red',
  GREY: 'bg-status-grey',
};

export function StatusDot({ status, label }: { status: PlotStatusKey; label: string }) {
  return (
    <span className="inline-flex items-center gap-2">
      <span aria-hidden="true" className={`size-3 shrink-0 rounded-full ${dots[status]}`} />
      <span>{label}</span>
    </span>
  );
}

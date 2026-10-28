import { useGetPlot } from '@nadel/api-client';
import { UserContent } from '../UserContent';
import { PlotPicker } from '../tariffs/PlotPicker';
import { Button } from '../ui';

/** One plot chosen by number search; once chosen it is shown with a button to choose again. */
export function PlotSelect({
  label,
  plotId,
  changeLabel,
  onChange,
}: {
  label: string;
  plotId: string | undefined;
  changeLabel: string;
  onChange: (plotId: string | undefined) => void;
}) {
  const plot = useGetPlot(plotId ?? '', { query: { enabled: plotId !== undefined } });
  if (plotId === undefined) {
    return <PlotPicker label={label} excludeIds={[]} onPick={(picked) => onChange(picked.id)} />;
  }
  return (
    <div className="flex flex-col gap-1">
      <span className="text-sm font-medium text-ink-muted">{label}</span>
      <div className="flex items-center gap-3">
        <span className="font-medium">{plot.data ? <UserContent>{plot.data.number}</UserContent> : '…'}</span>
        <Button variant="secondary" onClick={() => onChange(undefined)}>
          {changeLabel}
        </Button>
      </div>
    </div>
  );
}

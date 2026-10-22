import { MeterResource, type Meter, type MeterResource as MeterResourceValue } from '@nadel/api-client';

/** One card of the round: all meters of one plot, or a single non-plot meter. */
export interface RoundGroup {
  key: string;
  plot?: NonNullable<Meter['plot']>;
  meters: Meter[];
}

/** Groups the round's meters by plot, keeping the server order (a group sits where its first meter is). */
export function groupRound(meters: readonly Meter[]): RoundGroup[] {
  const groups: RoundGroup[] = [];
  const byPlot = new Map<string, RoundGroup>();
  for (const meter of meters) {
    if (!meter.plot) {
      groups.push({ key: meter.id, meters: [meter] });
      continue;
    }
    const existing = byPlot.get(meter.plot.id);
    if (existing) {
      existing.meters.push(meter);
      continue;
    }
    const group: RoundGroup = { key: `plot:${meter.plot.id}`, plot: meter.plot, meters: [meter] };
    byPlot.set(meter.plot.id, group);
    groups.push(group);
  }
  return groups;
}

const RESOURCE_KEY = 'nadel.readings.resource';
const RESOURCES: readonly string[] = Object.values(MeterResource);

/** The remembered resource filter of this browser; `undefined` (all) on any storage failure or invalid value. */
export function readStoredResource(): MeterResourceValue | undefined {
  try {
    const value = window.localStorage.getItem(RESOURCE_KEY);
    return Object.values(MeterResource).find((r) => r === value && RESOURCES.includes(r));
  } catch {
    return undefined;
  }
}

/** Remembers the resource filter (`undefined` = all); a storage failure is ignored. */
export function storeResource(resource: MeterResourceValue | undefined): void {
  try {
    if (resource) window.localStorage.setItem(RESOURCE_KEY, resource);
    else window.localStorage.removeItem(RESOURCE_KEY);
  } catch {
    // storage unavailable: the filter just is not remembered
  }
}

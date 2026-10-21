import type { Meter, MeterResource } from '@nadel/api-client';
import type { Messages } from '@nadel/shared';

/** What a meter is called on screen: its name, or the serial number of a plot meter (user content either way). */
export function meterLabel(meter: Pick<Meter, 'name' | 'serial'>): string {
  return meter.name ?? meter.serial;
}

/** The unit of a resource's readings, from the catalogs. */
export function resourceUnit(t: Messages, resource: MeterResource): string {
  return resource === 'WATER' ? t.units.m3 : t.units.kwh;
}

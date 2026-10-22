import type { Meter } from '@nadel/api-client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { plotMeter, wellMeter } from './test/meterFixtures';
import { groupRound, readStoredResource, storeResource } from './readingsRound';

const water: Meter = { ...plotMeter, id: 'm9', serial: 'WT-1', resource: 'WATER' };
const other: Meter = { ...plotMeter, id: 'm8', plot: { id: 'p8', number: '8' } };

describe('groupRound', () => {
  it('puts the meters of one plot into one group at the position of its first meter', () => {
    const groups = groupRound([plotMeter, other, water, wellMeter]);
    expect(groups.map((g) => g.meters.map((m) => m.id))).toEqual([['m1', 'm9'], ['m8'], [wellMeter.id]]);
    expect(groups[0]?.plot?.id).toBe('p7');
    expect(groups[2]?.plot).toBeUndefined();
  });
});

describe('remembered resource filter', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it('stores and reads the choice, and "all" clears it', () => {
    storeResource('WATER');
    expect(readStoredResource()).toBe('WATER');
    storeResource(undefined);
    expect(readStoredResource()).toBeUndefined();
  });

  it('ignores an invalid stored value', () => {
    window.localStorage.setItem('nadel.readings.resource', 'GAS');
    expect(readStoredResource()).toBeUndefined();
  });

  it('falls back to all when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readStoredResource()).toBeUndefined();
    expect(() => storeResource('WATER')).not.toThrow();
    expect(() => storeResource(undefined)).not.toThrow();
  });
});

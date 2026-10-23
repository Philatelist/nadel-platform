import {
  RecalculationAction,
  RecalculationCauseKind,
  RecalculationCauseType,
  RecalculationChangeReason,
} from '@nadel/api-client';
import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { actionLabel, causeKindLabel, causeLabel, causeTypeLabel, changeReasonLabel } from './recalcLabels';

describe.each(['uk', 'en'] as const)('recalcLabels (%s)', (locale) => {
  const t = messages(locale);
  const distinct = (labels: string[]) => {
    expect(labels.every((l) => l.length > 0)).toBe(true);
    expect(new Set(labels).size).toBe(labels.length);
  };

  it('names every action, cause kind, cause type and change reason', () => {
    distinct(Object.values(RecalculationAction).map((v) => actionLabel(t, v)));
    distinct(Object.values(RecalculationCauseKind).map((v) => causeKindLabel(t, v)));
    distinct(Object.values(RecalculationCauseType).map((v) => causeTypeLabel(t, v)));
    distinct(Object.values(RecalculationChangeReason).map((v) => changeReasonLabel(t, v)));
  });

  it('prefers the specific cause over its kind', () => {
    expect(causeLabel(t, { cause: 'TARIFF_VERSION', causeType: 'TARIFF_VERSION_DELETE' })).toBe(
      t.recalculation.causeType.TARIFF_VERSION_DELETE,
    );
    expect(causeLabel(t, { cause: 'READING' })).toBe(t.recalculation.causeKind.READING);
  });
});

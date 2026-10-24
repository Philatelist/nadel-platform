import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { fieldDescription } from './reportTemplateFields';

const en = messages('en');

describe('fieldDescription', () => {
  it('maps a key to its nested text', () => {
    expect(fieldDescription(en, { field: 'generatedAt' })).toBe('Date and time the file was made');
    expect(fieldDescription(en, { field: 'cooperative.iban' })).toBe('IBAN (settings, requisites)');
    expect(fieldDescription(en, { field: 'rows.plots.debt.total' })).toBe(
      'Debt of the plot at the end of the period',
    );
    expect(fieldDescription(en, { field: 'totals.debt' })).toBe(
      'Debt of all active plots at the end of the period',
    );
  });

  it('fills {chargeType} in a charge type family', () => {
    expect(fieldDescription(en, { field: 'rows.plots.debt.byChargeType' }, 'Water')).toBe(
      'Debt of the plot for Water',
    );
    expect(fieldDescription(en, { field: 'totals.charged.byChargeType' }, 'Water')).toMatch(
      /^Charged for Water /,
    );
  });

  it('is undefined for a code with no text or one that stops at a group', () => {
    expect(fieldDescription(en, { field: 'nope.nothing' })).toBeUndefined();
    expect(fieldDescription(en, { field: 'rows.plots' })).toBeUndefined();
  });

  it('speaks Ukrainian from the Ukrainian catalog', () => {
    expect(fieldDescription(messages('uk'), { field: 'period.from' })).toBe(
      messages('uk').reportTemplates.fields.period.from,
    );
  });
});

import { render } from '@testing-library/react';
import { messages } from '@nadel/shared';
import { describe, expect, it } from 'vitest';
import { auditValue } from './auditValue';

const text = (node: ReturnType<typeof auditValue>, locale: 'uk' | 'en') => {
  const { container } = render(<>{node}</>);
  void locale;
  return container.textContent;
};

describe('auditValue for meters', () => {
  const uk = messages('uk');

  it('turns codes, dates and readings into catalog text and locale format', () => {
    expect(text(auditValue('METER', 'kind', 'COMMON_CONSUMER', uk, 'uk'), 'uk')).toBe('Спільний споживач');
    expect(text(auditValue('METER', 'resource', 'WATER', uk, 'uk'), 'uk')).toBe('Вода');
    expect(text(auditValue('METER', 'initialReading', '8205.500', uk, 'uk'), 'uk')).toBe('8\u00a0205,5');
    expect(text(auditValue('METER', 'installedOn', '2026-06-01', uk, 'uk'), 'uk')).toBe('01.06.2026');
    expect(text(auditValue('METER', 'removedOn', '2026-06-02', uk, 'uk'), 'uk')).toBe('02.06.2026');
  });

  it('formats a reading change through the meter value and day formats', () => {
    expect(text(auditValue('METER_READING', 'takenOn', '2026-10-05', uk, 'uk'), 'uk')).toBe('05.10.2026');
    expect(text(auditValue('METER_READING', 'value', '8205.000', uk, 'uk'), 'uk')).toBe('8\u00a0205');
  });

  it('marks typed text as user content', () => {
    const { container } = render(<>{auditValue('METER', 'serial', 'ЕМ-1', uk, 'uk')}</>);
    expect(container.querySelector('[data-user-content]')?.textContent).toBe('ЕМ-1');
  });
});

describe('auditValue for replacements', () => {
  const uk = messages('uk');

  it('shows the serial of the new meter and a rejection reason as user content', () => {
    const serial = render(<>{auditValue('METER', 'replacementMeter', 'ЕМ-2', uk, 'uk')}</>);
    expect(serial.container.querySelector('[data-user-content]')?.textContent).toBe('ЕМ-2');
    const reason = render(<>{auditValue('METER_READING', 'reason', 'Нечітке фото', uk, 'uk')}</>);
    expect(reason.container.querySelector('[data-user-content]')?.textContent).toBe('Нечітке фото');
    expect(uk.audit.fields.replacementMeter).toBeTruthy();
  });
});

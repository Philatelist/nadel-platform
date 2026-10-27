import { describe, expect, it } from 'vitest';
import { ApiError } from '@nadel/api-client';
import { messages } from '@nadel/shared';
import { reportTemplateErrorMessage } from './reportTemplateError';

const refusal = (code: string) =>
  new ApiError(400, { code: 'TEMPLATE_INVALID', errors: [{ field: 'file', code }] });

/** Every reason the server gives for a template file it will not store (ProblemCodes: NOT_XLSX .. ARRAY_ACROSS_ROW). */
const reasons = [
  'NOT_XLSX',
  'MACROS',
  'ACTIVE_CONTENT',
  'BROKEN_ZIP',
  'UNPACKED_TOO_LARGE',
  'TWO_ROW_SETS',
  'ARRAY_ACROSS_ROW',
] as const;

describe('reportTemplateErrorMessage', () => {
  for (const locale of ['en', 'uk'] as const) {
    const t = messages(locale);

    it(`gives every refused file its own text in ${locale}: macros, not a workbook, damaged, a bomb`, () => {
      const texts = reasons.map((reason) => reportTemplateErrorMessage(t, refusal(reason)));
      for (const [index, reason] of reasons.entries()) {
        expect(texts[index], reason).toBe(t.reportTemplates.invalid[reason]);
        expect(texts[index], reason).not.toBe('');
        expect(texts[index], reason).not.toBe(t.reportTemplates.errors.TEMPLATE_INVALID);
      }
      expect(new Set(texts).size).toBe(reasons.length);
    });

    it(`falls back to the general text for a reason it does not know in ${locale}`, () => {
      expect(reportTemplateErrorMessage(t, refusal('SOMETHING_NEW'))).toBe(
        t.reportTemplates.errors.TEMPLATE_INVALID,
      );
    });

    it(`says the file is too large for the size refusals in ${locale}`, () => {
      const tooLarge = t.reportTemplates.errors.FILE_TOO_LARGE;
      expect(reportTemplateErrorMessage(t, new ApiError(413, {}))).toBe(tooLarge);
      expect(reportTemplateErrorMessage(t, new ApiError(422, { code: 'TEMPLATE_TOO_LARGE' }))).toBe(
        t.reportTemplates.errors.TEMPLATE_TOO_LARGE,
      );
      expect(t.reportTemplates.errors.TEMPLATE_TOO_LARGE).toBe(tooLarge);
    });
  }

  it('shows no server text for a refusal', () => {
    const t = messages('en');
    const error = new ApiError(400, {
      code: 'TEMPLATE_INVALID',
      title: 'java.lang.IllegalStateException at org.apache.poi',
      errors: [{ field: 'file', code: 'MACROS' }],
    });
    expect(reportTemplateErrorMessage(t, error)).toBe(
      'The file contains macros. Save it as a plain .xlsx without macros.',
    );
  });
});

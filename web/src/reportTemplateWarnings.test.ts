import { describe, expect, it } from 'vitest';
import { parseGenerationWarnings } from './reportTemplateWarnings';

const detail = (value: unknown, encoding: 'base64url' | 'base64' = 'base64url') =>
  Buffer.from(JSON.stringify(value), 'utf-8').toString(encoding);
const headers = (init: Record<string, string>) => new Headers(init);

describe('parseGenerationWarnings', () => {
  it('reads the count and the decoded details', () => {
    const warnings = [
      { code: 'VALUE_UNKNOWN', sheet: 'Sheet1', cell: 'B7', placeholder: 'totals.debt', reason: 'NOT_SET' },
    ];
    expect(
      parseGenerationWarnings(
        headers({ 'X-Report-Warnings': '3', 'X-Report-Warnings-Detail': detail(warnings) }),
      ),
    ).toEqual({ count: 3, details: warnings });
  });

  it('keeps Cyrillic cell texts intact (UTF-8)', () => {
    const warnings = [{ code: 'UNKNOWN_PLACEHOLDER', sheet: 'Аркуш «Вода»', placeholder: 'ділянка' }];
    const result = parseGenerationWarnings(
      headers({ 'X-Report-Warnings': '1', 'X-Report-Warnings-Detail': detail(warnings) }),
    );
    expect(result.details).toEqual(warnings);
  });

  it.each([
    ['without padding', (s: string) => s.replace(/=+$/, '')],
    ['with padding', (s: string) => s + '='.repeat((4 - (s.length % 4)) % 4)],
  ])('decodes base64url %s', (_name, shape) => {
    const warnings = [{ code: 'VALUE_UNKNOWN', sheet: '~~~???' }];
    const encoded = shape(detail(warnings));
    expect(
      parseGenerationWarnings(headers({ 'X-Report-Warnings': '1', 'X-Report-Warnings-Detail': encoded }))
        .details,
    ).toEqual(warnings);
  });

  it('accepts the - and _ alphabet', () => {
    const warnings = [{ code: 'VALUE_UNKNOWN', sheet: '>>>???~~~' }];
    const encoded = detail(warnings);
    expect(encoded).toMatch(/[-_]/);
    expect(
      parseGenerationWarnings(headers({ 'X-Report-Warnings': '1', 'X-Report-Warnings-Detail': encoded }))
        .details,
    ).toEqual(warnings);
  });

  it('gives an empty list but keeps the count when the detail is malformed', () => {
    for (const bad of [
      '!!!not base64!!!',
      detail('x').slice(0, 3),
      Buffer.from('{oops').toString('base64url'),
    ]) {
      expect(
        parseGenerationWarnings(headers({ 'X-Report-Warnings': '4', 'X-Report-Warnings-Detail': bad })),
      ).toEqual({ count: 4, details: [] });
    }
  });

  it('drops items that are not warnings and unknown codes', () => {
    const encoded = detail([{ code: 'NOPE' }, 7, null, { code: 'RICH_TEXT_FLATTENED' }]);
    expect(
      parseGenerationWarnings(headers({ 'X-Report-Warnings': '2', 'X-Report-Warnings-Detail': encoded }))
        .details,
    ).toEqual([{ code: 'RICH_TEXT_FLATTENED' }]);
  });

  it('is zero without the headers or without response headers', () => {
    expect(parseGenerationWarnings(headers({}))).toEqual({ count: 0, details: [] });
    expect(parseGenerationWarnings(undefined)).toEqual({ count: 0, details: [] });
    expect(parseGenerationWarnings(headers({ 'X-Report-Warnings': '0' })).count).toBe(0);
  });
});

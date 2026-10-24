import { describe, expect, it } from 'vitest';
import { maxReportTemplateFileBytes, validateReportTemplateFile } from './reportTemplateFile';

describe('validateReportTemplateFile', () => {
  it('accepts an .xlsx file up to 2 MiB, in any letter case', () => {
    expect(validateReportTemplateFile({ name: 'form.xlsx', size: 10 })).toBeUndefined();
    expect(
      validateReportTemplateFile({ name: 'FORM.XLSX', size: maxReportTemplateFileBytes }),
    ).toBeUndefined();
  });

  it('rejects another extension before looking at the size', () => {
    expect(validateReportTemplateFile({ name: 'form.xlsm', size: 10 })).toBe('FILE_NOT_XLSX');
    expect(validateReportTemplateFile({ name: 'form.xls', size: maxReportTemplateFileBytes + 1 })).toBe(
      'FILE_NOT_XLSX',
    );
  });

  it('rejects a file over 2 MiB', () => {
    expect(validateReportTemplateFile({ name: 'form.xlsx', size: maxReportTemplateFileBytes + 1 })).toBe(
      'FILE_TOO_LARGE',
    );
  });
});

import { describe, expect, it } from 'vitest';
import { maxImportFileBytes, validateImportFile } from './importFile';

describe('validateImportFile', () => {
  it('accepts an .xlsx file case-insensitively', () => {
    expect(validateImportFile({ name: 'plots.xlsx', size: 10 })).toBeUndefined();
    expect(validateImportFile({ name: 'PLOTS.XLSX', size: 10 })).toBeUndefined();
  });

  it('rejects other extensions', () => {
    expect(validateImportFile({ name: 'plots.xls', size: 10 })).toBe('FILE_NOT_XLSX');
    expect(validateImportFile({ name: 'plots.csv', size: 10 })).toBe('FILE_NOT_XLSX');
    expect(validateImportFile({ name: 'xlsx', size: 10 })).toBe('FILE_NOT_XLSX');
  });

  it('accepts exactly 10 MiB and rejects one byte more', () => {
    expect(maxImportFileBytes).toBe(10 * 1024 * 1024);
    expect(validateImportFile({ name: 'a.xlsx', size: maxImportFileBytes })).toBeUndefined();
    expect(validateImportFile({ name: 'a.xlsx', size: maxImportFileBytes + 1 })).toBe('FILE_TOO_LARGE');
  });
});

export const maxImportFileBytes = 10 * 1024 * 1024;

export type ImportFileError = 'FILE_NOT_XLSX' | 'FILE_TOO_LARGE';

/** Client-side check before upload, with the same codes as the server: extension first, then size. */
export function validateImportFile(file: { name: string; size: number }): ImportFileError | undefined {
  if (!file.name.toLowerCase().endsWith('.xlsx')) return 'FILE_NOT_XLSX';
  if (file.size > maxImportFileBytes) return 'FILE_TOO_LARGE';
  return undefined;
}

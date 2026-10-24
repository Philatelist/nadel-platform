export const maxReportTemplateFileBytes = 2 * 1024 * 1024;

export type ReportTemplateFileError = 'FILE_NOT_XLSX' | 'FILE_TOO_LARGE';

/** Client-side check before upload: extension first, then size. The server stays authoritative. */
export function validateReportTemplateFile(file: {
  name: string;
  size: number;
}): ReportTemplateFileError | undefined {
  if (!file.name.toLowerCase().endsWith('.xlsx')) return 'FILE_NOT_XLSX';
  if (file.size > maxReportTemplateFileBytes) return 'FILE_TOO_LARGE';
  return undefined;
}

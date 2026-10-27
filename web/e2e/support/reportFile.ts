// Reads a generated report workbook for the end-to-end specs: sheet names and the text of all cells.
import { readFile } from 'node:fs/promises';
import { zipEntryText } from './xlsxRead';

function decode(xml: string): string {
  const named: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
  return xml.replace(/&(#x[0-9a-f]+|#[0-9]+|amp|lt|gt|quot|apos);/gi, (match: string, ref: string) =>
    ref.toLowerCase().startsWith('#x')
      ? String.fromCodePoint(parseInt(ref.slice(2), 16))
      : ref.startsWith('#')
        ? String.fromCodePoint(parseInt(ref.slice(1), 10))
        : (named[ref.toLowerCase()] ?? match),
  );
}

function optionalEntry(zip: Buffer, name: string): string {
  try {
    return zipEntryText(zip, name);
  } catch {
    return '';
  }
}

export interface ReportWorkbook {
  sheetNames: string[];
  /** All cell text (shared and inline strings, plus numeric values) of every sheet, one string per cell. */
  cells: string[];
}

/** Reads the workbook saved at `path`. */
export async function readReportWorkbook(path: string): Promise<ReportWorkbook> {
  const zip = await readFile(path);
  const workbook = decode(zipEntryText(zip, 'xl/workbook.xml'));
  const sheetNames = [...workbook.matchAll(/<sheet\b[^>]*\bname="([^"]*)"/g)].map((m) => m[1] ?? '');
  const texts = (xml: string) =>
    [...xml.matchAll(/<t(?:\s[^>]*)?>([^<]*)<\/t>/g)].map((m) => decode(m[1] ?? ''));
  const cells = texts(optionalEntry(zip, 'xl/sharedStrings.xml'));
  for (let i = 1; i <= sheetNames.length; i++) {
    const sheet = optionalEntry(zip, `xl/worksheets/sheet${i}.xml`);
    cells.push(...texts(sheet));
    // numeric cells (a typed value such as the year) keep their number in <v>; shared-string cells (t="s") hold an index
    for (const m of sheet.matchAll(/<c\b([^>]*)>(?:(?!<\/c>)[\s\S])*?<v>([^<]*)<\/v>/g))
      if (!/\bt="(s|str|inlineStr)"/.test(m[1] ?? '')) cells.push(m[2] ?? '');
  }
  return { sheetNames, cells };
}

// Reads text parts out of an .xlsx for the end-to-end specs without a dependency: walks the zip's central directory
// and inflates entries with Node's built-in zlib.
import { inflateRawSync } from 'node:zlib';

/** Returns the named zip entry as UTF-8 text. Throws when the entry is missing. */
export function zipEntryText(zip: Buffer, entryName: string): string {
  const eocd = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) throw new Error('not a zip file');
  const count = zip.readUInt16LE(eocd + 10);
  let pos = zip.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    const method = zip.readUInt16LE(pos + 10);
    const compressedSize = zip.readUInt32LE(pos + 20);
    const nameLength = zip.readUInt16LE(pos + 28);
    const extraLength = zip.readUInt16LE(pos + 30);
    const commentLength = zip.readUInt16LE(pos + 32);
    const localOffset = zip.readUInt32LE(pos + 42);
    const name = zip.toString('utf8', pos + 46, pos + 46 + nameLength);
    if (name === entryName) {
      const dataStart =
        localOffset + 30 + zip.readUInt16LE(localOffset + 26) + zip.readUInt16LE(localOffset + 28);
      const raw = zip.subarray(dataStart, dataStart + compressedSize);
      return (method === 0 ? raw : inflateRawSync(raw)).toString('utf8');
    }
    pos += 46 + nameLength + extraLength + commentLength;
  }
  throw new Error(`zip entry not found: ${entryName}`);
}

/** Replaces XML character references and the predefined entities, so sheet names written as `&#x414;…` compare as text. */
function decodeXmlEntities(xml: string): string {
  const named: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
  return xml.replace(/&(#x[0-9a-f]+|#[0-9]+|amp|lt|gt|quot|apos);/gi, (match: string, ref: string) =>
    ref.startsWith('#x') || ref.startsWith('#X')
      ? String.fromCodePoint(parseInt(ref.slice(2), 16))
      : ref.startsWith('#')
        ? String.fromCodePoint(parseInt(ref.slice(1), 10))
        : (named[ref.toLowerCase()] ?? match),
  );
}

/** The workbook's sheet names and shared strings as decoded text, enough to check a template's language. */
export function readWorkbookText(file: Buffer): { workbook: string; strings: string } {
  return {
    workbook: decodeXmlEntities(zipEntryText(file, 'xl/workbook.xml')),
    strings: decodeXmlEntities(zipEntryText(file, 'xl/sharedStrings.xml')),
  };
}

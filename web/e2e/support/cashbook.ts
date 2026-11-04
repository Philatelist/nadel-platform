import { deflateSync } from 'node:zlib';
import type { Browser, Page } from '@playwright/test';
import { api, expectCreated } from './api';
import { loginAs } from './login';

/** A separate browser context signed in as the person (English UI), so that two people can act in one test. */
export async function signedInPage(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, email);
  return page;
}

export interface AccountCard {
  kind: 'CASH' | 'BANK';
  balance?: string;
  unresolvedDifference?: string;
  lastCount?: { id: string; countedAt: string };
  months?: { month: string; status: 'NOT_RECONCILED' | 'RECONCILED' | 'NEEDS_RECHECK' }[];
}

/** The two money accounts as the server shows them today (amounts stay strings). */
export async function accountCards(page: Page): Promise<Record<'CASH' | 'BANK', AccountCard>> {
  const list = expectCreated<AccountCard[]>(await api(page, 'GET', '/money-accounts'), 200);
  const cash = list.find((a) => a.kind === 'CASH');
  const bank = list.find((a) => a.kind === 'BANK');
  if (!cash || !bank) throw new Error('the cooperative has no cash or bank account');
  return { CASH: cash, BANK: bank };
}

/** An amount string in hundredths, for exact arithmetic on contract amounts without floating point. */
export function cents(amount: string | undefined): number {
  if (amount === undefined) throw new Error('the account has no balance (no opening balance)');
  const negative = amount.startsWith('-');
  const [int = '0', frac = '00'] = (negative ? amount.slice(1) : amount).split('.');
  const value = Number(int) * 100 + Number(frac.padEnd(2, '0'));
  return negative ? -value : value;
}

/** The digits of a displayed amount ("₴9,000.00" gives "900000"), to compare a screen with a contract amount. */
export function digitsOf(text: string | null): string {
  return (text ?? '').replace(/\D/g, '');
}

/** The digits of a contract amount without its sign ("-9000.00" gives "900000"). */
export function amountDigits(amount: string): string {
  return amount.replace(/\D/g, '');
}

export const RECEIPT_WIDTH = 120;
export const RECEIPT_HEIGHT = 80;

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(bytes: Buffer): number {
  let c = 0xffffffff;
  for (const b of bytes) c = (crcTable[(c ^ b) & 0xff] ?? 0) ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

/** A real 120x80 PNG (a grey gradient, 8-bit greyscale), standing in for the photo of a receipt; built here, no binary file. */
export const RECEIPT_PNG: Buffer = (() => {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(RECEIPT_WIDTH, 0);
  header.writeUInt32BE(RECEIPT_HEIGHT, 4);
  header[8] = 8; // bit depth
  header[9] = 0; // greyscale
  const rows: Buffer[] = [];
  for (let y = 0; y < RECEIPT_HEIGHT; y++) {
    const row = Buffer.alloc(1 + RECEIPT_WIDTH); // filter byte 0 + pixels
    for (let x = 0; x < RECEIPT_WIDTH; x++) row[1 + x] = (x * 2 + y) % 256;
    rows.push(row);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', header),
    pngChunk('IDAT', deflateSync(Buffer.concat(rows))),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
})();

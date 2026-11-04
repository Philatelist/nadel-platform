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

/** A small valid PNG, standing in for the photo of a receipt (the server checks the type by content). */
export const RECEIPT_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

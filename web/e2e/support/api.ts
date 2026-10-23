import type { Page } from '@playwright/test';
import { messages } from '@nadel/shared';

export interface ApiResult {
  status: number;
  body: string;
}

/** One API call with the signed-in person's token: for set-up, clean-up and the refusals the UI cannot send. */
export async function api(page: Page, method: string, path: string, body?: unknown): Promise<ApiResult> {
  return page.evaluate(
    async ({ method, path, body }) => {
      const key = Object.keys(window.sessionStorage).find((k) => k.startsWith('oidc.user:'));
      const token = key
        ? (JSON.parse(window.sessionStorage.getItem(key) ?? '{}') as { access_token?: string })
        : {};
      const response = await fetch(`/api/v1${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${token.access_token ?? ''}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return { status: response.status, body: await response.text() };
    },
    { method, path, body },
  );
}

/** A calendar day in the cooperative's time zone (Europe/Kyiv) as `YYYY-MM-DD`, shifted by `offsetDays`. */
export function kyivDay(offsetDays = 0): string {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Kyiv' }).format(new Date());
  const day = new Date(`${today}T00:00:00Z`);
  day.setUTCDate(day.getUTCDate() + offsetDays);
  return day.toISOString().slice(0, 10);
}

/** A day as the English UI prints it: "21 Oct 2026" (day without a leading zero, short month, no time zone shift). */
export function shortDay(isoDay: string): string {
  const [year = '', month = '01', day = '01'] = isoDay.slice(0, 10).split('-');
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${Number(day)} ${months[Number(month) - 1] ?? month} ${year}`;
}

/** "2026-10" as the English UI prints a billing month: "October 2026" (names from the i18n catalog, not hard-coded). */
export function monthLabel(month: string): string {
  const [year = '', number = ''] = month.split('-');
  const names: Readonly<Record<string, string | undefined>> = messages('en').billing.monthNames;
  return `${names[number] ?? number} ${year}`;
}

export interface BillingMonthRow {
  month: string;
  status: 'OPEN' | 'CLOSED';
}

/**
 * The demo ledger hangs on the day the database was first loaded: its open month is the current month and its closed
 * month the one before. Read them from the API instead of naming calendar months, so the specs do not age.
 */
export async function demoMonths(page: Page): Promise<{ open: string; closed: string }> {
  const months = expectCreated<BillingMonthRow[]>(await api(page, 'GET', '/billing-months'), 200);
  const open = months.find((m) => m.status === 'OPEN')?.month;
  const closed = months.find((m) => m.status === 'CLOSED')?.month;
  if (!open || !closed)
    throw new Error(`the demo ledger has no open and closed month: ${JSON.stringify(months)}`);
  return { open, closed };
}

/**
 * A past range for the losses and common consumers previews, in days before today: 83 to 53 days back. It ends after
 * the last reading of plot 20 (75 days back in the demo), so quarter Б has no reading after the range and is a
 * warning, while quarter А has readings inside it. It lies wholly before the demo runs (19 to 5 days back).
 */
export function pastRange(): { from: string; to: string } {
  return { from: kyivDay(-83), to: kyivDay(-53) };
}

/** Parses a JSON response that must have the given status, or throws with the body for a readable failure. */
export function expectCreated<T>(result: ApiResult, status = 201): T {
  if (result.status !== status) throw new Error(`expected ${status}, got ${result.status}: ${result.body}`);
  return JSON.parse(result.body) as T;
}

export interface CreatedPlot {
  id: string;
  number: string;
}
export interface CreatedMember {
  id: string;
  name: string;
  lastName: string;
}

/** A plot with a unique number, created with the chairman's token. */
export async function createPlot(page: Page, number: string): Promise<CreatedPlot> {
  const created = expectCreated<{ id: string }>(
    await api(page, 'POST', '/plots', { number, kind: 'PLOT', areaM2: 400, street: 'E2E Lane' }),
  );
  return { id: created.id, number };
}

/** A member whose last name is unique, so that the member search finds exactly this one. */
export async function createMember(page: Page, lastName: string): Promise<CreatedMember> {
  const created = expectCreated<{ id: string }>(
    await api(page, 'POST', '/members', { lastName, firstName: 'Test' }),
  );
  return { id: created.id, name: `${lastName} Test`, lastName };
}

/** A change of owner through the API; `owners` empty ends the ownership. */
export async function changeOwners(
  page: Page,
  plotId: string,
  effectiveFrom: string,
  owners: { memberId: string; share: string }[],
): Promise<void> {
  expectCreated(await api(page, 'POST', `/plots/${plotId}/ownership-changes`, { effectiveFrom, owners }));
}

/**
 * Leaves no owner, archives the plot and the members. Ownership rows and plots with history cannot be deleted,
 * so the archived records stay behind under unique E2E numbers and names. Best effort: never throws.
 */
export async function archiveLeftovers(
  page: Page,
  plots: CreatedPlot[],
  members: CreatedMember[],
  endOwnershipOn: string,
): Promise<void> {
  for (const plot of plots) {
    await api(page, 'POST', `/plots/${plot.id}/ownership-changes`, {
      effectiveFrom: endOwnershipOn,
      owners: [],
    }).catch(() => undefined);
    await api(page, 'POST', `/plots/${plot.id}/archive`).catch(() => undefined);
  }
  for (const member of members)
    await api(page, 'POST', `/members/${member.id}/archive`).catch(() => undefined);
}

/**
 * Best effort: gets rid of a test meter. Readings a charge uses cannot be deleted and a meter with readings cannot
 * be deleted, so it is deleted when possible and otherwise removed with its last reading as the final one (a
 * removed meter no longer blocks archiving its plot). Never throws.
 */
export async function retireMeter(page: Page, meterId: string): Promise<void> {
  const history = await api(page, 'GET', `/meters/${meterId}/readings`).catch(() => undefined);
  if (history?.status === 200)
    for (const r of (JSON.parse(history.body) as { readings: { id: string; status: string }[] }).readings) {
      if (r.status === 'PENDING')
        await api(page, 'POST', `/readings/${r.id}/reject`, { reason: 'E2E cleanup' }).catch(() => undefined);
      if (r.status === 'APPROVED') await api(page, 'DELETE', `/readings/${r.id}`).catch(() => undefined);
    }
  const deleted = await api(page, 'DELETE', `/meters/${meterId}`).catch(() => undefined);
  if (deleted?.status === 204) return;
  const current = await api(page, 'GET', `/meters/${meterId}`).catch(() => undefined);
  const last =
    current?.status === 200
      ? (JSON.parse(current.body) as { lastReading?: { value?: string } }).lastReading?.value
      : undefined;
  await api(page, 'POST', `/meters/${meterId}/remove`, {
    removedOn: kyivDay(0),
    finalReading: last ?? '0',
  }).catch(() => undefined);
}

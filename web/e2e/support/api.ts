import type { Page } from '@playwright/test';

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

/** A day as the English UI prints it in the ownership lists (dd.MM.yyyy-style short date with a four-digit year, no time zone shift). */
export function shortDay(isoDay: string): string {
  return new Intl.DateTimeFormat('en-US', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(isoDay));
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

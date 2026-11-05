// @layer: e2e
// @spec: 020-go-live-polish
// @regression
//
// Inviting a member into the system (feature 020, slice 5): the chairman invites a throwaway member with an e-mail,
// the letter arrives in Mailpit, the invitee sets a password on the public page, signs in at Keycloak and the card
// then shows «Has access». Also: no e-mail disables the invite with a reason, the treasurer only sees the status,
// and an e-mail of another cooperative is refused. Needs the demo cooperative's mailbox (Mailpit) and Keycloak.
import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { archiveLeftovers, createMember, kyivDay, type CreatedMember } from './support/api';
import { loginAs, signInAtKeycloak } from './support/login';
import { letterLink, waitForLetterTo } from './support/mailpit';

const t = messages('en');
const created: CreatedMember[] = [];

function unique(): string {
  return randomBytes(4).toString('hex');
}

async function chairman(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await loginAs(page, 'chairman@demo.local');
  return page;
}

async function throwawayMember(page: Page, email?: string): Promise<CreatedMember> {
  const member = await createMember(page, `Invite${unique()}`, email);
  created.push(member);
  return member;
}

test.afterEach(async ({ browser }) => {
  if (created.length === 0) return;
  const page = await chairman(browser);
  await archiveLeftovers(page, [], created.splice(0), kyivDay(0));
  await page.context().close();
});

test('the chairman invites a member who sets a password and signs in @smoke', async ({ browser }) => {
  const admin = await chairman(browser);
  const email = `invite-${unique()}@e2e.local`;
  const member = await throwawayMember(admin, email);

  await admin.goto(`/members/${member.id}`);
  await expect(admin.getByText(t.memberAccess.status.NOT_INVITED)).toBeVisible();
  await admin.getByRole('button', { name: t.memberAccess.invite }).click();
  await expect(admin.getByText(/waiting for a password/)).toBeVisible();

  const link = await letterLink((await waitForLetterTo(email)).id);

  const invitee = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await invitee.newPage();
  await page.addInitScript(() => window.localStorage.setItem('nadel.locale', 'en'));
  await page.goto(link);
  await expect(page.getByLabel(t.setPassword.email)).toHaveValue(email);
  const password = `Pw-${randomBytes(6).toString('hex')}`;
  await page.getByLabel(t.setPassword.password, { exact: true }).fill(password);
  await page.getByLabel(t.setPassword.confirm).fill(password);
  await page.getByRole('button', { name: t.setPassword.submit }).click();
  await expect(page.getByText(t.setPassword.done)).toBeVisible();
  await page.getByRole('button', { name: t.setPassword.signIn }).click();
  await signInAtKeycloak(page, email, password);
  await expect(page.getByRole('banner').getByRole('button', { name: t.auth.signOut })).toBeVisible();
  await invitee.close();

  await admin.reload();
  await expect(admin.getByText(/Has access/)).toBeVisible();
  await admin.context().close();
});

test('a member without an e-mail cannot be invited and the reason is shown', async ({ browser }) => {
  const admin = await chairman(browser);
  const member = await throwawayMember(admin);
  await admin.goto(`/members/${member.id}`);
  await expect(admin.getByRole('button', { name: t.memberAccess.invite })).toBeDisabled();
  await expect(admin.getByText(t.memberAccess.blocked.EMAIL_MISSING)).toBeVisible();
  await admin.context().close();
});

test('the treasurer sees the access status but no buttons', async ({ browser }) => {
  const admin = await chairman(browser);
  const member = await throwawayMember(admin, `invite-${unique()}@e2e.local`);
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const treasurer = await context.newPage();
  await loginAs(treasurer, 'treasurer@demo.local');
  await treasurer.goto(`/members/${member.id}`);
  await expect(treasurer.getByText(t.memberAccess.status.NOT_INVITED)).toBeVisible();
  await expect(treasurer.getByRole('button', { name: t.memberAccess.invite })).toHaveCount(0);
  await context.close();
  await admin.context().close();
});

test('an e-mail used in another cooperative is refused', async ({ browser }) => {
  const admin = await chairman(browser);
  const member = await throwawayMember(admin, 'member@other.local');
  await admin.goto(`/members/${member.id}`);
  await admin.getByRole('button', { name: t.memberAccess.invite }).click();
  await expect(admin.getByText(t.problem.EMAIL_IN_OTHER_COOPERATIVE)).toBeVisible();
  await admin.context().close();
});

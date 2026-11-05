// @layer: e2e
// @spec: 019-in-app-help
// @regression
//
// Acceptance specs of functional-spec.md of "Help inside the application" (019): the «?» button and side panel (§2.3),
// the contents and search (§2.4, §2.5), what each person sees (§2.6) and the public help with its head tags (§2.7).
// Titles carry the criteria as "§2.x". The UI texts come from the i18n catalogs; page titles and body text are read from
// the Markdown in docs/help, so a reworded page does not break the specs. None of these specs changes stored data.
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test, type Browser, type Page } from '@playwright/test';
import { format, messages, type Locale } from '@nadel/shared';
import { api, expectCreated } from './support/api';
import { loginAs } from './support/login';

const uk = messages('uk');
const en = messages('en');
const HELP_DIR = resolve(process.cwd(), '..', 'docs', 'help');

function pageSource(locale: Locale, id: string): string {
  return readFileSync(resolve(HELP_DIR, locale, `${id}.md`), 'utf8');
}

/** The `title:` of a help page's front matter, without quotes. */
function titleOf(locale: Locale, id: string): string {
  const match = /^title:\s*(.+)$/m.exec(pageSource(locale, id));
  if (!match) throw new Error(`no title in ${locale}/${id}.md`);
  return match[1]!.trim().replace(/^"(.*)"$/, '$1');
}

/** The first sentence-sized piece of a page's first paragraph: text that only that page contains. */
function leadOf(locale: Locale, id: string): string {
  const body = pageSource(locale, id).split(/^---$/m)[2] ?? '';
  const paragraph = body
    .split('\n')
    .map((line) => line.trim())
    .find((line) => line !== '' && !line.startsWith('#') && !line.includes('{{') && !line.startsWith('>'));
  if (!paragraph) throw new Error(`no lead paragraph in ${locale}/${id}.md`);
  return paragraph.slice(0, 40);
}

async function signedIn(browser: Browser, email: string, locale: Locale = 'uk'): Promise<Page> {
  const context = await browser.newContext({ baseURL: 'http://localhost:5173', locale: 'en-US' });
  const page = await context.newPage();
  await page.addInitScript((value) => window.localStorage.setItem('nadel.locale', value), locale);
  await loginAs(page, email, { keepLocale: true });
  return page;
}

/** The visitor's previous choice of the language (the browser of the specs prefers English otherwise). */
async function chooseLocale(page: Page, locale: Locale): Promise<void> {
  await page.addInitScript((value) => window.localStorage.setItem('nadel.locale', value), locale);
}

const contents = (page: Page) => page.getByRole('main');
const pageLink = (page: Page, title: string) =>
  contents(page).getByRole('link', { name: title, exact: true });
/** The non-modal help panel: a complementary landmark labelled by the page title (the menu is another one). */
const helpPanel = (page: Page, title: string) => page.getByRole('complementary', { name: title });
const menu = (page: Page) => page.locator('#app-menu nav');
const searchBox = (page: Page, locale: Locale) =>
  page.getByRole('searchbox', { name: messages(locale).help.search });

test.describe('§2.3 the «?» button and the side panel', () => {
  test('§2.3 #2: the member presses «?» on «Мій кабінет» and the panel shows the account page, opening it in full goes to the help section @smoke', async ({
    browser,
  }) => {
    const member = await signedIn(browser, 'member@demo.local');
    try {
      await member.goto('/account');
      await member.getByRole('button', { name: uk.help.open }).click();
      const panel = helpPanel(member, titleOf('uk', 'account'));
      await expect(panel.getByRole('heading', { level: 2, name: titleOf('uk', 'account') })).toBeVisible();
      await expect(panel.getByText(leadOf('uk', 'account'), { exact: false })).toBeVisible();

      await panel.getByRole('link', { name: uk.help.openFull }).click();
      await expect(member).toHaveURL(/\/help\/account$/);
      await expect(member.getByRole('heading', { level: 1, name: titleOf('uk', 'account') })).toBeVisible();
    } finally {
      await member.context().close();
    }
  });

  test('§2.3 #1: the treasurer presses «?» on «Платежі», the panel shows the payments page and the list stays visible behind it', async ({
    browser,
  }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/payments');
      const heading = treasurer.getByRole('heading', { level: 1, name: uk.payments.title });
      await expect(heading).toBeVisible();
      await treasurer.getByRole('button', { name: uk.help.open }).click();
      const panel = helpPanel(treasurer, titleOf('uk', 'payments'));
      await expect(panel.getByRole('heading', { level: 2, name: titleOf('uk', 'payments') })).toBeVisible();
      // the screen stays where it was: its own heading is still rendered and visible beside the panel
      await expect(heading).toBeVisible();
      await expect(heading).not.toBeHidden();

      await panel.getByRole('button', { name: uk.help.close }).click();
      await expect(panel).toHaveCount(0);
    } finally {
      await treasurer.context().close();
    }
  });

  test.describe('on a phone', () => {
    test('§2.3 #4: the panel covers the whole screen and can be closed', async ({ browser }) => {
      const member = await signedIn(browser, 'member@demo.local');
      try {
        await member.setViewportSize({ width: 390, height: 780 });
        await member.goto('/account');
        await member.getByRole('button', { name: uk.help.open }).click();
        const panel = helpPanel(member, titleOf('uk', 'account'));
        await expect(panel).toBeVisible();
        const box = await panel.boundingBox();
        expect(box?.width).toBeGreaterThanOrEqual(388);
        expect(box?.height).toBeGreaterThanOrEqual(778);
        await expect(panel.getByRole('button', { name: uk.help.back })).toBeVisible();
        await panel.getByRole('button', { name: uk.help.back }).click();
        await expect(panel).toHaveCount(0);
      } finally {
        await member.context().close();
      }
    });
  });

  test('§2.3 #6: the panel is not modal: it is a complementary landmark, there is no dialog and no backdrop, and Esc closes it', async ({
    browser,
  }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/payments');
      const heading = treasurer.getByRole('heading', { level: 1, name: uk.payments.title });
      await expect(heading).toBeVisible();
      await treasurer.getByRole('button', { name: uk.help.open }).click();
      const panel = helpPanel(treasurer, titleOf('uk', 'payments'));
      await expect(panel).toBeVisible();
      await expect(treasurer.getByRole('dialog')).toHaveCount(0);
      await expect(treasurer.locator('[aria-modal="true"]')).toHaveCount(0);
      // the page stays clickable: the heading is the topmost element at its centre, not covered by a backdrop
      const covered = await heading.evaluate((element) => {
        const box = element.getBoundingClientRect();
        const top = document.elementFromPoint(box.x + 2, box.y + box.height / 2);
        return top !== null && !element.contains(top) && top !== element;
      });
      expect(covered, 'the screen is covered by something').toBe(false);

      await treasurer.keyboard.press('Escape');
      await expect(panel).toHaveCount(0);
    } finally {
      await treasurer.context().close();
    }
  });

  test("§2.3 #7: with the panel open a menu click opens the new screen and the panel then shows that screen's help", async ({
    browser,
  }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/payments');
      await treasurer.getByRole('button', { name: uk.help.open }).click();
      await expect(helpPanel(treasurer, titleOf('uk', 'payments'))).toBeVisible();

      await menu(treasurer).getByRole('link', { name: uk.nav.members, exact: true }).click();
      await expect(treasurer).toHaveURL(/\/members$/);
      await expect(treasurer.getByRole('heading', { level: 1, name: uk.nav.members })).toBeVisible();
      await expect(helpPanel(treasurer, titleOf('uk', 'members'))).toBeVisible();
      await expect(helpPanel(treasurer, titleOf('uk', 'payments'))).toHaveCount(0);
    } finally {
      await treasurer.context().close();
    }
  });

  test('§2.3 #8: with the panel open the main content still scrolls', async ({ browser }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.setViewportSize({ width: 1280, height: 500 });
      await treasurer.goto('/plots');
      await expect(treasurer.getByRole('heading', { level: 1, name: uk.nav.plots })).toBeVisible();
      const main = treasurer.getByRole('main');
      await expect(main.locator('tbody tr').first()).toBeVisible();
      await treasurer.getByRole('button', { name: uk.help.open }).click();
      await expect(helpPanel(treasurer, titleOf('uk', 'plots'))).toBeVisible();

      const scrollable = await main.evaluate((element) => element.scrollHeight - element.clientHeight);
      expect(scrollable, 'the plots list is taller than the window').toBeGreaterThan(50);
      const before = await main.evaluate((element) => element.scrollTop);
      await main.hover();
      await treasurer.mouse.wheel(0, 300);
      await expect.poll(() => main.evaluate((element) => element.scrollTop)).toBeGreaterThan(before);
      // the panel is still there after scrolling
      await expect(helpPanel(treasurer, titleOf('uk', 'plots'))).toBeVisible();
    } finally {
      await treasurer.context().close();
    }
  });

  test('§2.3 #5 negative: a screen without a help page shows no «?» button', async ({ browser }) => {
    const member = await signedIn(browser, 'member@demo.local');
    try {
      await member.goto('/no-such-screen-for-help');
      await expect(member.getByRole('banner')).toBeVisible();
      await expect(member.getByRole('button', { name: uk.help.open })).toHaveCount(0);
    } finally {
      await member.context().close();
    }
  });
});

test.describe('§2.4 and §2.6 the contents', () => {
  test('§2.6 #1 and §2.4 #1: the chairman sees every page, grouped by section in the order of the contents', async ({
    browser,
  }) => {
    const chairman = await signedIn(browser, 'chairman@demo.local');
    try {
      await chairman.goto('/help');
      const ids = readdirSync(resolve(HELP_DIR, 'uk'))
        .filter((name) => name.endsWith('.md'))
        .map((name) => name.slice(0, -3));
      await expect(contents(chairman).locator('a[href^="/help/"]')).toHaveCount(ids.length);

      const headings = await contents(chairman).getByRole('heading', { level: 2 }).allTextContents();
      expect(headings).toEqual(Object.values(uk.help.sections));
    } finally {
      await chairman.context().close();
    }
  });

  test('§2.6 #2 and #4: the member sees only the pages for everyone, the member pages and the bill pages, and «сторнувати» does not find the payment page', async ({
    browser,
  }) => {
    const member = await signedIn(browser, 'member@demo.local');
    try {
      await member.goto('/help');
      await expect(pageLink(member, titleOf('uk', 'account'))).toBeVisible();
      await expect(pageLink(member, titleOf('uk', 'member-bill'))).toBeVisible();
      // the member's «Початок роботи» is its own page, the board one is not offered
      await expect(contents(member).locator('a[href="/help/member-getting-started"]')).toBeVisible();
      await expect(contents(member).locator('a[href="/help/getting-started"]')).toHaveCount(0);
      // the plots list and the plot card are board screens: a member reaches their plots from the account
      for (const id of [
        'plots',
        'plot-card',
        'payments',
        'payments-enter',
        'payment-card',
        'members',
        'meters',
      ]) {
        await expect(pageLink(member, titleOf('uk', id))).toHaveCount(0);
      }
      for (const section of [
        'plots-members',
        'payments-cash',
        'documents-delivery',
        'reports',
        'settings',
      ] as const) {
        await expect(
          contents(member).getByRole('heading', { level: 2, name: uk.help.sections[section] }),
        ).toHaveCount(0);
      }

      await searchBox(member, 'uk').fill('сторнувати');
      await expect(pageLink(member, titleOf('uk', 'payment-card'))).toHaveCount(0);
    } finally {
      await member.context().close();
    }
  });

  test('§2.6 #5: the member opens the payment-entry help by address and sees «Цей розділ вам недоступний» with a link to the contents and none of the page text', async ({
    browser,
  }) => {
    const member = await signedIn(browser, 'member@demo.local');
    try {
      await member.goto('/help/payments-enter');
      await expect(member.getByRole('heading', { level: 1, name: uk.help.hidden })).toBeVisible();
      const toContents = member.getByRole('link', { name: uk.help.toContents });
      await expect(toContents).toBeVisible();
      await expect(contents(member).getByText(leadOf('uk', 'payments-enter'))).toHaveCount(0);
      await expect(member.getByRole('heading', { name: titleOf('uk', 'payments-enter') })).toHaveCount(0);

      await toContents.click();
      await expect(member).toHaveURL(/\/help$/);
    } finally {
      await member.context().close();
    }
  });

  test('§2.6 #3: the electrician (the demo role «Електрик»: meters and readings) sees of the board pages only readings and meters', async ({
    browser,
  }) => {
    const electrician = await signedIn(browser, 'electrician@demo.local');
    try {
      // precondition: the stock demo role. A database where an earlier run left extra rights on it needs a reset
      // (`docker compose -f infra/docker-compose.yml down -v`).
      const me = expectCreated<{ permissions: string[] }>(await api(electrician, 'GET', '/me'), 200);
      expect([...me.permissions].sort(), 'rights of the demo electrician').toEqual([
        'METERS_VIEW',
        'READINGS_ENTER',
      ]);

      await electrician.goto('/help');
      for (const id of ['meters', 'meter-card', 'meter-tree', 'readings-round']) {
        await expect(pageLink(electrician, titleOf('uk', id))).toBeVisible();
      }
      for (const id of ['payments', 'members', 'tariffs', 'charges', 'reports']) {
        await expect(pageLink(electrician, titleOf('uk', id))).toHaveCount(0);
      }
      for (const section of [
        'plots-members',
        'tariffs-charges',
        'payments-cash',
        'documents-delivery',
        'reports',
        'settings',
      ] as const) {
        await expect(
          contents(electrician).getByRole('heading', { level: 2, name: uk.help.sections[section] }),
        ).toHaveCount(0);
      }
      // the account help page is for everyone (the name in the header opens the account); the pages for owners and
      // members (the bill, the member's start) are not
      await expect(pageLink(electrician, titleOf('uk', 'account'))).toBeVisible();
      await expect(pageLink(electrician, titleOf('uk', 'member-bill'))).toHaveCount(0);
      await expect(contents(electrician).locator('a[href="/help/member-getting-started"]')).toHaveCount(0);
    } finally {
      await electrician.context().close();
    }
  });
});

test.describe('§2.6 the member menu and the way back', () => {
  test('§2.6 #6: the member menu leads with «Мій кабінет», has no «Ділянки» and no «Довідка»; /plots lands on the account; the app name leads to the account', async ({
    browser,
  }) => {
    const member = await signedIn(browser, 'member@demo.local');
    try {
      await expect(menu(member).getByRole('link').first()).toHaveText(uk.nav.account);
      await expect(menu(member).getByRole('link', { name: uk.nav.plots, exact: true })).toHaveCount(0);
      await expect(
        member.locator('#app-menu').getByRole('link', { name: uk.help.title, exact: true }),
      ).toHaveCount(0);

      await member.goto('/plots');
      await expect(member).toHaveURL(/\/account$/);
      await expect(member.getByRole('heading', { level: 1, name: uk.nav.account })).toBeVisible();

      await member.goto('/help');
      await member.getByRole('banner').getByRole('link').first().click();
      await expect(member).toHaveURL(/\/account$/);
    } finally {
      await member.context().close();
    }
  });

  test('§2.6 #7: the treasurer keeps «Ділянки» and «Довідка» in the menu', async ({ browser }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await expect(menu(treasurer).getByRole('link', { name: uk.nav.plots, exact: true })).toBeVisible();
      await expect(
        treasurer.locator('#app-menu').getByRole('link', { name: uk.help.title, exact: true }),
      ).toBeVisible();
    } finally {
      await treasurer.context().close();
    }
  });

  test('§2.3 #9: «Відкрити повністю» opens the help page and «← Назад до …» returns to the screen it was opened from', async ({
    browser,
  }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/payments');
      await treasurer.getByRole('button', { name: uk.help.open }).click();
      await helpPanel(treasurer, titleOf('uk', 'payments'))
        .getByRole('link', { name: uk.help.openFull })
        .click();
      await expect(treasurer).toHaveURL(/\/help\/payments$/);
      await expect(
        treasurer.getByRole('heading', { level: 1, name: titleOf('uk', 'payments') }),
      ).toBeVisible();

      await treasurer
        .getByRole('main')
        .getByRole('link', { name: /^← Назад до «/ })
        .first()
        .click();
      await expect(treasurer).toHaveURL(/\/payments$/);
      await expect(treasurer.getByRole('heading', { level: 1, name: uk.payments.title })).toBeVisible();
    } finally {
      await treasurer.context().close();
    }
  });

  test('§2.3 #10: a help page opened directly goes back to the home of the person: the account for a member, the plots for the treasurer', async ({
    browser,
  }) => {
    const member = await signedIn(browser, 'member@demo.local');
    try {
      await member.goto('/help/account');
      await expect(member.getByRole('heading', { level: 1, name: titleOf('uk', 'account') })).toBeVisible();
      const back = member
        .getByRole('main')
        .getByRole('link', { name: format(uk.help.backTo, { name: uk.nav.account }) });
      await expect(back).toBeVisible();
      await back.click();
      await expect(member).toHaveURL(/\/account$/);
    } finally {
      await member.context().close();
    }
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/help');
      await treasurer
        .getByRole('main')
        .getByRole('link', { name: format(uk.help.backTo, { name: uk.nav.plots }) })
        .click();
      await expect(treasurer).toHaveURL(/\/plots$/);
    } finally {
      await treasurer.context().close();
    }
  });
});

test.describe('§2.5 search', () => {
  test('§2.5 #1: the treasurer searches «сторнувати платіж» on /help and opens the payment page from the results', async ({
    browser,
  }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/help');
      await searchBox(treasurer, 'uk').fill('сторнувати платіж');
      await pageLink(treasurer, titleOf('uk', 'payment-card')).click();
      await expect(treasurer).toHaveURL(/\/help\/payment-card$/);
      await expect(
        treasurer.getByRole('heading', { level: 1, name: titleOf('uk', 'payment-card') }),
      ).toBeVisible();
    } finally {
      await treasurer.context().close();
    }
  });

  test('§2.5 #2 and #4: a word with one wrong letter still finds the page, in Ukrainian and, after the switch, in English', async ({
    browser,
  }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/help');
      await searchBox(treasurer, 'uk').fill('сторнуваты платіж');
      await expect(pageLink(treasurer, titleOf('uk', 'payment-card'))).toBeVisible();

      await treasurer.getByRole('button', { name: en.language.name }).click();
      await expect(searchBox(treasurer, 'en')).toBeVisible();
      await searchBox(treasurer, 'en').fill('reverce payment');
      await expect(pageLink(treasurer, titleOf('en', 'payment-card'))).toBeVisible();
      // the Ukrainian title is not offered in the English interface
      await expect(pageLink(treasurer, titleOf('uk', 'payment-card'))).toHaveCount(0);
    } finally {
      await treasurer.context().close();
    }
  });

  test('§2.5 #3: the name of a button finds the page that explains it', async ({ browser }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/help');
      await searchBox(treasurer, 'uk').fill(uk.payments.enter);
      await expect(pageLink(treasurer, titleOf('uk', 'payments-enter'))).toBeVisible();
    } finally {
      await treasurer.context().close();
    }
  });

  test('§2.5 #5 and #6 negative: a word that appears nowhere shows «Нічого не знайдено», and searching makes no request to the server', async ({
    browser,
  }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/help');
      // let the contents and the bundle settle, then count what leaves the browser while searching
      await expect(pageLink(treasurer, titleOf('uk', 'payments'))).toBeVisible();
      const requests: string[] = [];
      treasurer.on('request', (request) => requests.push(request.url()));

      await searchBox(treasurer, 'uk').fill('сторнувати');
      await expect(pageLink(treasurer, titleOf('uk', 'payment-card'))).toBeVisible();
      await searchBox(treasurer, 'uk').fill('qqqzzzxxx');
      await expect(treasurer.getByRole('status').filter({ hasText: uk.help.noResults })).toBeVisible();
      expect(requests.filter((url) => new URL(url).pathname.startsWith('/api/'))).toEqual([]);
    } finally {
      await treasurer.context().close();
    }
  });
});

test.describe('§2.7 the public help', () => {
  test('§2.7 #1, #2 and #3: a visitor who is not signed in reads /help/payments-enter with its screenshot, makes no /api/ request, sees «Увійти», and it starts the sign-in', async ({
    page,
  }) => {
    await chooseLocale(page, 'uk');
    const apiRequests: string[] = [];
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.startsWith('/api/')) apiRequests.push(request.url());
    });

    await page.goto('/help/payments-enter');
    await expect(
      page.getByRole('heading', { level: 1, name: titleOf('uk', 'payments-enter') }),
    ).toBeVisible();
    await expect(page.getByText(leadOf('uk', 'payments-enter'))).toBeVisible();
    // the page is read in full with its screenshots
    const shot = page.getByRole('main').locator('img').first();
    await expect(shot).toBeVisible();
    await expect.poll(() => shot.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);

    // search in the public help leaves the browser quiet as well
    await page.goto('/help');
    await searchBox(page, 'uk').fill('сторнувати платіж');
    await expect(pageLink(page, titleOf('uk', 'payment-card'))).toBeVisible();

    const signIn = page.getByRole('banner').getByRole('button', { name: uk.auth.signIn });
    await expect(signIn).toBeVisible();
    expect(apiRequests, 'requests to /api/').toEqual([]);

    await signIn.click();
    await expect(page.locator('#username')).toBeVisible();
  });

  test('§2.7 #4 and #5: a help page carries title, description, canonical address, preview image and robots index, and an application screen keeps noindex', async ({
    browser,
  }) => {
    const treasurer = await signedIn(browser, 'treasurer@demo.local');
    try {
      await treasurer.goto('/help/payments-enter');
      const title = titleOf('uk', 'payments-enter');
      await expect(treasurer.getByRole('heading', { level: 1, name: title })).toBeVisible();
      await expect(treasurer).toHaveTitle(new RegExp(title));
      await expect(treasurer.locator('meta[name="description"]')).toHaveAttribute('content', /.{20,}/);
      await expect(treasurer.locator('link[rel="canonical"]')).toHaveAttribute(
        'href',
        /\/help\/payments-enter$/,
      );
      await expect(treasurer.locator('meta[property="og:image"]')).toHaveAttribute(
        'content',
        /\/help\/img\/uk\/.+\.png$/,
      );
      await expect(treasurer.locator('meta[name="robots"]')).toHaveAttribute('content', /^index/);

      // negative: an application screen is not offered to search engines
      await treasurer.goto('/payments');
      await expect(treasurer.getByRole('heading', { level: 1, name: uk.payments.title })).toBeVisible();
      await expect(treasurer.locator('meta[name="robots"]')).toHaveAttribute('content', /noindex/);
      await expect(treasurer.locator('link[rel="canonical"]')).toHaveCount(0);
    } finally {
      await treasurer.context().close();
    }
  });

  test('§2.7 #4 anonymous: the public page carries the same head tags without a sign-in', async ({
    page,
  }) => {
    await chooseLocale(page, 'uk');
    await page.goto('/help/payments-enter');
    await expect(
      page.getByRole('heading', { level: 1, name: titleOf('uk', 'payments-enter') }),
    ).toBeVisible();
    await expect(page.locator('meta[name="description"]')).toHaveAttribute('content', /.{20,}/);
    await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href', /\/help\/payments-enter$/);
    await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', /^index/);
  });

  test('020: the set-password page opens signed out without a token, shows the invalid text and stays on the app', async ({
    page,
  }) => {
    await chooseLocale(page, 'uk');
    await page.goto('/set-password');
    await expect(page.getByText(messages('uk').setPassword.invalid)).toBeVisible();
    expect(page.url()).toContain('localhost:5173/set-password');
  });
});

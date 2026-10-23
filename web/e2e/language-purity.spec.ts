// @layer: e2e
// @spec: fix/one-language
// @regression
//
// The UI shows exactly one language. For every main screen:
//   - in Ukrainian, no Latin-letter word may be visible, except the allowlist below (brand and file-format names);
//   - in English, no Cyrillic text may be visible outside `[data-user-content]` (the demo data is Ukrainian, like
//     real data, and is shown as entered).
// What is checked: text nodes of visible elements, plus the attribute values `aria-label`, `placeholder`, `title`
// and `alt`, and the document title. What is skipped: `[data-user-content]` subtrees, real code elements
// (CODE/KBD/SAMP/PRE/TT) and `[data-code]`, the third-party map attribution, and in attributes any value that is a
// user-content text present on the page (an aria-label such as "Edit <member name>" is fine, the name is stripped
// before the check). Font family is deliberately not a skip criterion: tables set monospace on numeric headers.
// Deliberate exceptions in Ukrainian, besides the allowlist: e-mail addresses, URLs and IBANs (patterns), file
// extensions such as ".xlsx" (FILE_EXTENSION), and the digit mask letter "N" in input hints (MASK).
import { expect, test, type Browser, type Page } from '@playwright/test';
import { messages } from '@nadel/shared';
import { kyivDay, pastRange } from './support/api';
import { loginAs } from './support/login';

type Locale = 'uk' | 'en';

interface Finding {
  where: string;
  word: string;
  context: string;
}

/** Words that may stay in Latin script in the Ukrainian UI: product and format names, as they are written everywhere. */
const LATIN_ALLOWLIST = ['Excel', 'Telegram', 'Viber', 'QR', 'IBAN'];

/**
 * Language names written in their own language on the language switcher ("ENG"/"English", "УКР"/"Українська"):
 * the one place where the other language is meant to be visible, so that a person can find their way back.
 */
const AUTONYMS = ['ENG', 'English', 'УКР', 'Українська'];

async function openSession(browser: Browser, email: string, locale: Locale): Promise<Page> {
  const context = await browser.newContext({
    baseURL: 'http://localhost:5173',
    locale: locale === 'uk' ? 'uk-UA' : 'en-US',
    viewport: { width: 1280, height: 900 },
  });
  const page = await context.newPage();
  await page.addInitScript((value) => {
    try {
      window.localStorage.setItem('nadel.locale', value);
    } catch {
      // storage unavailable: the browser locale applies
    }
  }, locale);
  await loginAs(page, email, { keepLocale: true });
  return page;
}

/** Waits for the screen to settle: heading present, no "loading" placeholder, no request in flight for a moment. */
async function settle(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(() =>
        [...document.querySelectorAll('main *')].some(
          (el) =>
            el.children.length === 0 && /^(Loading…|Завантаження…)$/.test((el.textContent ?? '').trim()),
        ),
      ),
    )
    .toBe(false);
  await page.waitForLoadState('networkidle');
  // the generic error state is a failure of the screen, not a text to check
  const errors = [messages('uk').state.error, messages('en').state.error];
  const shown = await page.evaluate(
    (texts) =>
      (document.querySelector('main')?.textContent ?? '').includes(texts[0] ?? '') ||
      (document.querySelector('main')?.textContent ?? '').includes(texts[1] ?? ''),
    errors,
  );
  expect(shown, 'the generic error state is shown').toBe(false);
}

/** DOM walk in the page: returns every visible piece of text with the path of its nearest element. */
async function collect(page: Page): Promise<{ text: string; where: string; attribute: boolean }[]> {
  return page.evaluate(() => {
    const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'CODE', 'KBD', 'PRE', 'SAMP', 'TT', 'CANVAS']);
    const out: { text: string; where: string; attribute: boolean }[] = [];
    const userTexts = [...document.querySelectorAll('[data-user-content]')]
      .map((el) => (el.textContent ?? '').trim())
      .filter((t) => t.length > 0)
      .sort((a, b) => b.length - a.length);

    const pathOf = (el: Element): string => {
      const parts: string[] = [];
      for (let node: Element | null = el; node && node !== document.body; node = node.parentElement) {
        const id = node.id ? `#${node.id}` : '';
        const roleName = node.getAttribute('role');
        const label = node.getAttribute('aria-label');
        const role = roleName ? `[role=${roleName}${roleName === 'group' && label ? `:${label}` : ''}]` : '';
        parts.unshift(`${node.tagName.toLowerCase()}${id}${role}`);
        if (parts.length >= 5) break;
      }
      return parts.join(' > ');
    };

    const skipped = (el: Element): boolean => {
      if (SKIP_TAGS.has(el.tagName)) return true;
      if (el.closest('[data-user-content], [data-code], .maplibregl-ctrl-attrib')) return true;
      const style = getComputedStyle(el);
      return style.display === 'none' || style.visibility === 'hidden';
    };

    const visibleChain = (el: Element): boolean => {
      for (let node: Element | null = el; node; node = node.parentElement) {
        if (skipped(node)) return false;
      }
      return true;
    };

    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = (node.textContent ?? '').trim();
      const parent = node.parentElement;
      if (text && parent && visibleChain(parent)) out.push({ text, where: pathOf(parent), attribute: false });
    }
    for (const el of document.body.querySelectorAll('[aria-label],[placeholder],[title],[alt]')) {
      if (!visibleChain(el)) continue;
      for (const name of ['aria-label', 'placeholder', 'title', 'alt']) {
        let value = el.getAttribute(name);
        if (!value) continue;
        for (const user of userTexts) value = value.split(user).join(' ');
        if (value.trim()) out.push({ text: value.trim(), where: `${pathOf(el)} @${name}`, attribute: true });
      }
    }
    let title = document.title;
    for (const user of userTexts) title = title.split(user).join(' ');
    out.push({ text: title.trim(), where: 'document.title', attribute: true });
    return out;
  });
}

const EMAIL = /[\w.+-]+@[\w-]+(\.[\w-]+)+/g;
const URL_LIKE = /\b(https?:\/\/|www\.)\S+/g;
const IBAN = /\bUA\d{2}[\dA-Z]{10,}\b/g;
const FILE_EXTENSION = /\.(xlsx|xls|csv|pdf|png|jpe?g|json|txt)\b/gi;
/** Input masks such as "NNNNNNNNNN:NN:NNN:NNNN" or "N" in an address hint stand for digits, they are not words. */
const MASK = /\bN+\b/g;
const LATIN_WORD = /[A-Za-z]+(?:['’][A-Za-z]+)*/g;
const CYRILLIC_WORD = /[\u0400-\u04FF]+(?:['’][\u0400-\u04FF]+)*/g;

/** An autonym is allowed only inside the language switcher group, found by its catalog aria-label. */
const isAutonym = (word: string, where: string, locale: Locale): boolean =>
  AUTONYMS.includes(word) && where.includes(`[role=group:${messages(locale).nav.language}]`);

function check(locale: Locale, items: { text: string; where: string }[]): Finding[] {
  const findings: Finding[] = [];
  for (const { text, where } of items) {
    if (locale === 'uk') {
      const stripped = text
        .replace(EMAIL, ' ')
        .replace(URL_LIKE, ' ')
        .replace(IBAN, ' ')
        .replace(FILE_EXTENSION, ' ')
        .replace(MASK, ' ');
      for (const word of stripped.match(LATIN_WORD) ?? []) {
        if (!LATIN_ALLOWLIST.includes(word) && !isAutonym(word, where, locale))
          findings.push({ where, word, context: text.slice(0, 80) });
      }
    } else {
      for (const word of text.match(CYRILLIC_WORD) ?? []) {
        if (isAutonym(word, where, locale)) continue;
        findings.push({ where, word, context: text.slice(0, 80) });
      }
    }
  }
  return findings;
}

async function hrefs(page: Page, pattern: RegExp): Promise<string[]> {
  const all = await page
    .getByRole('main')
    .locator('a[href]')
    .evaluateAll((links) => links.map((a) => a.getAttribute('href') ?? ''));
  return all.filter((href) => pattern.test(href));
}

async function auditScreen(page: Page, path: string, locale: Locale): Promise<Finding[]> {
  await page.goto(path);
  await settle(page);
  const items = await collect(page);
  return check(locale, items).map((f) => ({ ...f, where: `${path}: ${f.where}` }));
}

/** Checks what is on the screen now (an open dialog, a preview), labelled with `where`. */
async function auditNow(page: Page, where: string, locale: Locale): Promise<Finding[]> {
  await page.waitForLoadState('networkidle');
  return check(locale, await collect(page)).map((f) => ({ ...f, where: `${where}: ${f.where}` }));
}

function report(findings: Finding[]): string {
  return findings.map((f) => `"${f.word}" in ${f.where} (text: "${f.context}")`).join('\n');
}

const CHAIRMAN_SCREENS = [
  '/plots',
  '/plots/new',
  '/members',
  '/members/new',
  '/account',
  '/tariffs',
  '/tariffs/new',
  '/charge-types',
  '/imports',
  '/audit',
  '/roles',
  '/settings',
  '/meters',
  '/readings',
  '/readings/approvals',
  '/settings/meter-tree',
  // feature 011: the months, the current month (the demo ledger anchors on today), the queue and the two range runs
  '/charges',
  `/charges/months/${kyivDay(0).slice(0, 7)}`,
  '/charges/not-charged',
  '/charges/losses',
  '/charges/common',
  '/no-such-screen',
];

for (const locale of ['uk', 'en'] as const) {
  test.describe(`one language: ${locale === 'uk' ? 'Ukrainian shows no Latin words' : 'English shows no Cyrillic text'}`, () => {
    test(`chairman: every main screen (${locale})`, async ({ browser }) => {
      test.setTimeout(180_000);
      const page = await openSession(browser, 'chairman@demo.local', locale);
      try {
        const findings: Finding[] = [];
        for (const path of CHAIRMAN_SCREENS) findings.push(...(await auditScreen(page, path, locale)));

        // cards and edit forms need an id: take the first one from the list
        await page.goto('/plots');
        await settle(page);
        const plotHref = (await hrefs(page, /^\/plots\/[0-9a-f-]{36}$/))[0];
        expect(plotHref, 'a plot link on the plots list').toBeTruthy();
        findings.push(...(await auditScreen(page, plotHref ?? '', locale)));
        findings.push(...(await auditScreen(page, `${plotHref ?? ''}/edit`, locale)));

        await page.goto('/members');
        await settle(page);
        const memberHref = (await hrefs(page, /^\/members\/[0-9a-f-]{36}$/))[0];
        expect(memberHref, 'a member link on the members list').toBeTruthy();
        findings.push(...(await auditScreen(page, memberHref ?? '', locale)));

        await page.goto('/tariffs');
        await settle(page);
        const tariffHref = (await hrefs(page, /^\/tariffs\/[0-9a-f-]{36}$/))[0];
        expect(tariffHref, 'a tariff link on the tariffs list').toBeTruthy();
        findings.push(...(await auditScreen(page, tariffHref ?? '', locale)));

        await page.goto('/meters');
        await settle(page);
        const meterHref = (await hrefs(page, /^\/meters\/[0-9a-f-]{36}$/))[0];
        expect(meterHref, 'a meter link on the meters list').toBeTruthy();
        findings.push(...(await auditScreen(page, meterHref ?? '', locale)));

        expect(report(findings), 'language mixes').toBe('');
      } finally {
        await page.context().close();
      }
    });

    test(`chairman: charges dialogs, previews and the plot account (${locale})`, async ({ browser }) => {
      test.setTimeout(120_000);
      const t = messages(locale);
      const page = await openSession(browser, 'chairman@demo.local', locale);
      try {
        const findings: Finding[] = [];
        // the month page with the run preview and the close confirmation (nothing is booked or closed)
        await page.goto(`/charges/months/${kyivDay(0).slice(0, 7)}`);
        await settle(page);
        await page.getByRole('button', { name: t.billing.run.action, exact: true }).click();
        await expect(page.getByRole('dialog').getByText(/\d/).first()).toBeVisible();
        await expect(page.getByText(t.billing.run.loading)).toHaveCount(0);
        findings.push(...(await auditNow(page, 'month run preview', locale)));
        await page.getByRole('button', { name: t.billing.run.cancel, exact: true }).click();
        await page.getByRole('button', { name: t.billing.close.action, exact: true }).click();
        findings.push(...(await auditNow(page, 'close month dialog', locale)));
        await page.getByRole('button', { name: t.billing.close.cancel, exact: true }).click();

        // a losses and a common consumers preview over a past range (previews write nothing)
        const range = await pastRange(page);
        for (const path of ['/charges/losses', '/charges/common']) {
          await page.goto(path);
          await settle(page);
          await page.getByLabel(t.billing.rangeRun.from, { exact: true }).fill(range.from);
          await page.getByLabel(t.billing.rangeRun.to, { exact: true }).fill(range.to);
          await page.getByRole('button', { name: t.billing.rangeRun.preview, exact: true }).click();
          await expect(page.getByRole('table').first()).toBeVisible();
          findings.push(...(await auditNow(page, `${path} preview`, locale)));
          await page.getByRole('button', { name: t.billing.runHistory.details, exact: true }).first().click();
          findings.push(...(await auditNow(page, `${path} run details`, locale)));
        }

        // the account of a plot: a charge with its calculation, the reverse dialog, the opening balance form
        await page.goto('/plots');
        await settle(page);
        const plotHref = (await hrefs(page, /^\/plots\/[0-9a-f-]{36}$/))[0];
        await page.goto(plotHref ?? '');
        await settle(page);
        await page.getByRole('button', { name: t.charges.details, exact: true }).first().click();
        findings.push(...(await auditNow(page, 'charge details', locale)));
        await page.getByRole('button', { name: t.charges.detail.close, exact: true }).first().click();
        await page.getByRole('button', { name: t.charges.reverse.action, exact: true }).first().click();
        findings.push(...(await auditNow(page, 'reverse dialog', locale)));
        await page.getByRole('button', { name: t.charges.reverse.cancel, exact: true }).click();
        await page.getByRole('button', { name: t.charges.opening.action, exact: true }).click();
        findings.push(...(await auditNow(page, 'opening balance form', locale)));

        expect(report(findings), 'language mixes').toBe('');
      } finally {
        await page.context().close();
      }
    });

    test(`member: own account and plots (${locale})`, async ({ browser }) => {
      const page = await openSession(browser, 'member@demo.local', locale);
      try {
        const findings: Finding[] = [];
        for (const path of ['/account', '/plots']) findings.push(...(await auditScreen(page, path, locale)));

        // the card of a plot the member owns
        await page.goto('/plots');
        await settle(page);
        const ownHref = (await hrefs(page, /^\/plots\/[0-9a-f-]{36}$/))[0];
        expect(ownHref, 'an own plot link on the member plots list').toBeTruthy();
        findings.push(...(await auditScreen(page, ownHref ?? '', locale)));

        // "My charges" with a charge's calculation
        await page.goto('/account');
        await settle(page);
        await page
          .getByRole('button', { name: messages(locale).charges.details, exact: true })
          .first()
          .click();
        findings.push(...(await auditNow(page, 'my charge details', locale)));
        expect(report(findings), 'language mixes').toBe('');
      } finally {
        await page.context().close();
      }
    });
  });
}

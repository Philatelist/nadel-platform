import { expect, type Locator, type Page } from '@playwright/test';

export const PHONE = { width: 360, height: 740 } as const;

/** The page and every open dialog (and any scroll container inside one) must not scroll sideways. */
export async function expectNoSideScroll(page: Page): Promise<void> {
  const offenders = await page.evaluate(() => {
    const found: string[] = [];
    const root = document.documentElement;
    if (root.scrollWidth > root.clientWidth)
      found.push(`page: scrollWidth ${root.scrollWidth} > clientWidth ${root.clientWidth}`);
    for (const dialog of document.querySelectorAll<HTMLElement>('[role=dialog], [aria-modal=true]')) {
      for (const el of [dialog, ...dialog.querySelectorAll<HTMLElement>('*')]) {
        const overflowX = getComputedStyle(el).overflowX;
        const scrolls = el === dialog || overflowX === 'auto' || overflowX === 'scroll';
        if (scrolls && el.scrollWidth > el.clientWidth + 0)
          found.push(`dialog container <${el.tagName.toLowerCase()}>: ${el.scrollWidth} > ${el.clientWidth}`);
      }
    }
    return found;
  });
  expect(offenders, 'side scroll').toEqual([]);
}

const TARGETS =
  'button, [role=button], a[href], input:not([type=hidden]):not([type=checkbox]):not([type=radio]), select, summary, label:has(input[type=checkbox]), label:has(input[type=radio])';

/**
 * Every visible control inside `root` (default: the whole page) must be at least 44 px tall.
 * Exempt: visually hidden elements (1 px or clipped, e.g. sr-only and the hidden file input) and inline links inside running
 * text (an `a` whose parent is a `p` or `li` that holds other text).
 */
export async function expectTouchTargets(page: Page, root?: Locator): Promise<void> {
  const scope = root ?? page.locator('body');
  const offenders = await scope.evaluate((container, selector) => {
    const nameOf = (el: HTMLElement): string =>
      (
        el.getAttribute('aria-label') ||
        el.textContent ||
        el.getAttribute('title') ||
        el.getAttribute('placeholder') ||
        (el as HTMLInputElement).value ||
        el.id ||
        ''
      )
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 60);
    const found: string[] = [];
    const nodes = [container as HTMLElement, ...container.querySelectorAll<HTMLElement>('*')].filter((el) =>
      el.matches(selector),
    );
    for (const el of nodes) {
      if (!el.checkVisibility({ checkOpacity: false, checkVisibilityCSS: true })) continue;
      const style = getComputedStyle(el);
      const box = el.getBoundingClientRect();
      if (box.width <= 1 || box.height <= 1) continue;
      if (style.clipPath !== 'none' || style.clip.startsWith('rect(')) continue;
      if (el.tagName === 'A') {
        const parent = el.parentElement;
        if (parent && (parent.tagName === 'P' || parent.tagName === 'LI')) {
          const own = Array.from(parent.childNodes)
            .filter((n) => n !== el)
            .map((n) => n.textContent ?? '')
            .join('')
            .trim();
          if (own.length > 0) continue;
        }
      }
      if (box.height < 44)
        found.push(
          `<${el.tagName.toLowerCase()}> "${nameOf(el)}" is ${Math.round(box.height * 10) / 10} px tall`,
        );
    }
    return found;
  }, TARGETS);
  expect(offenders, 'touch targets under 44 px').toEqual([]);
}

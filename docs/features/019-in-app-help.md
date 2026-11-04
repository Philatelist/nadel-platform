# 019. Help inside the application: per screen, per role, public

Input for `/feature-pipeline`. Requires every screen built so far (001–018, all merged). Planned right after 018, so that the help
covers every screen the cooperative «Надія» uses at go-live.

## 1. Goal

A person who opens the application sees, next to every screen, a help page that explains it:
- what every option and button on that screen does;
- when to use it;
- a worked example with the demo numbers;
- a screenshot.

The help shows what the person's own permissions allow them to use.

The same help is public, without signing in, so the board of another cooperative can read it before joining. The PDF
guides of `docs/guide/` stay and are built from the same text, so the help and the PDFs never drift apart.

**Decided with the owner (04.11.2026):**
- **Static help, no language model.** A model on the server costs money, answers slowly and can state wrong things
  about money with confidence. An assistant on top of this help can come later (phase 8).
- **The PDFs stay**, built from the same source.
- **The help is also public.**

## Context for the pipeline

Read together with:
- `CLAUDE.md`, including the guides rules and the one-language rule;
- `docs/guide/` (the board guide in `uk/` and `en/`, `scripts/capture-screens.mjs`, `scripts/build-pdf.mjs`,
  `README.md`);
- `web/src/App.tsx` (routes);
- the menu, `me.tsx` (`useCan`) and the identity README (permissions);
- the design system of 003.

General rules as in earlier features:
- i18n;
- design system 003;
- the verification levels of `CLAUDE.md`.

The board guide never mentions other cooperatives or the platform. The owner manual is not part of the help and stays a
PDF only, not public (it covers deployment and security).

## 2. Source of the help

**One Markdown file per help page**, under `docs/help/<lang>/` (`uk`, `en`), with front matter:

| Field | Meaning |
|---|---|
| `id` | stable slug, for example `payments-enter` |
| `title` | the page title |
| `routes` | the app routes it explains (`/payments/new`, `/payments/:id`), so that «?» on a screen opens it |
| `permissions` | any of these permissions makes the page "for you"; empty = everyone signed in; `member` = a current owner of a plot |
| `section` | group in the table of contents: «Початок роботи», «Ділянки і члени», «Тарифи і нарахування», «Лічильники», «Платежі і каса», «Документи і розсилка», «Звіти», «Налаштування», «Як система рахує» (Part II of the board guide), «Член кооперативу» |
| `order` | order inside the section |

**The body** of every screen page follows one template:
1. **What the screen is for** (one or two sentences).
2. **Who sees it** (the role names and the permission, in plain words).
3. **Every element:** each button, field, filter, tab and switch, in screen order. Each one gets:
   - what it does;
   - when to use it;
   - what happens after (including what cannot be undone and how to correct it);
   - the error messages it can show and what to do about each.
4. **Worked example** with the demo data: step by step, with the numbers the demo shows (for example plot 9, payment
   100,00 ₴, how it is allocated).
5. **Screenshots:** the screen, and the dialog or result of the main action, captured from the demo.
6. **Related pages** (links).

**Migration.** The content of `docs/guide/<lang>/board-guide.html` (Parts I and II) moves into these pages; nothing
written there is lost. The board guide PDF is then built from them (section 6). The owner manual and the Telegram
leaflet stay as they are.

## 3. In the application

- **The «?» button** in the header of every screen opens the help page whose `routes` match the current route, in a side
  panel. On a phone it opens full screen. The panel has «Відкрити повністю», which goes to `/help/<id>`.
- **`/help`:**
  - the table of contents by section;
  - search over all pages;
  - the page view with a sticky table of contents of its headings.
- **For a signed-in user:**
  - the help shows only the pages for the screens this user can open: the contents, the search results, the related
    links and the «?» panel all leave out pages whose `permissions` the user lacks, and the member pages unless the
    user owns a plot;
  - links inside a page to a hidden page are rendered as plain text;
  - opening `/help/<id>` of a hidden page directly shows «Цей розділ вам недоступний» with a link to the contents,
    and no content of the page.
  - The pages of «Як система рахує» that explain a member's own bill (tariffs, readings and electricity, losses and
    common consumers, recalculation, allocation of payments) are for everyone signed in. The rest of Part II follows
    the permissions of the screens it explains.
  - This is a filter for clarity, not a secret: the same pages are public (section 4), so no test treats a hidden page
    as confidential.
- **Search:**
  - client-side, over titles, headings and text, in the current UI language;
  - tolerant to a missing letter;
  - the search index is built at build time;
  - no request to the server.
- **Language:** the help follows the UI language. A page missing in one language is a build error, not a fallback to the
  other.

## 4. Public help

- **`/help` and `/help/<id>` open without signing in.** They show every page of the board guide and the member pages,
  with the contents in the order of the guide. The header has «Увійти».
- **Static content only.** The public help makes no API call and shows no data other than the screenshots of the
  fictional demo cooperative.
- **Each public page has:**
  - a title and description for search engines;
  - a canonical URL;
  - an Open Graph image (its first screenshot).

  `/help` is indexable. The rest of the application stays `noindex`.
- **A test stand** (`infra/stand`) or any server sends `X-Robots-Tag: noindex` for everything when the stand flag is
  set, so the stand does not compete with the real site in search.

## 5. Screenshots

- **Captured by `docs/guide/scripts/capture-screens.mjs`**, extended:
  - each help page lists its shots in the front matter: route, demo user, optional action steps (open a dialog, fill a
    field) and viewport (desktop or phone);
  - the script captures them for both languages into `web/public/help/img/<lang>/` (served with the app) and
    `docs/guide/img/<lang>/` (for the PDFs).
- `make guide-shots` captures everything. `make help-shots ID=<page id>` captures one page.
- A shot that cannot be taken fails the script with the page id. No silent placeholder.
- **Shots are taken with the demo data only.** A check refuses a capture run against any database whose cooperative is
  not the demo one.

## 6. PDFs

- `make guide` builds the board guide PDF in `uk` and `en` from `docs/help/<lang>/` in the order of the contents, with
  the screenshots, a table of contents and page numbers.
- The owner manual PDF is built as now.
- The PDFs carry the version (git tag or commit date) on the title page.

## 7. Keeping it complete (the CI check)

A test fails the build when:
- a route in `App.tsx` (except `*` and redirects) has no help page that lists it in `routes`;
- a menu item has no help page;
- a help page lists a route that no longer exists, or a screenshot file that is missing;
- a page exists in one language and not the other;
- a page lacks one of the template's sections (section 2).

`CLAUDE.md` gets one rule: a feature that adds or changes a screen adds or updates its help page and shots in the same
PR. The guides rule of `CLAUDE.md` points to `docs/help/` instead of the HTML. The owner commits `CLAUDE.md`.

## 8. Writing the content

- **Every screen that exists at the start of this feature gets its page.** That is every route of `App.tsx`; where two
  routes are one screen (a form for new and edit), one page lists both. Each page covers every element on the screen as
  the demo user with the most permissions sees it (the chairman), and the member view separately where it differs.
- **Style:**
  - Ukrainian for people who are not technical;
  - short sentences;
  - the button names exactly as on screen (from the i18n catalog, so a renamed button fails the check of section 7 when
    the page quotes the old name: pages quote buttons as `{{ui:payments.reverse}}`, rendered from the catalog);
  - no developer words.
- **English:** a faithful translation, same structure and shots.
- **Known traps the owner hit in review**, each covered in its page with a «Важливо» box:
  1. Two tariffs of one charge type cannot cover the same plots. The monthly water fee is charge type «Вода» (fixed per
     plot, only connected plots pay); the well pump's electricity is «Спільні споживачі» with the split «Підключені
     ділянки». The water type may need to be activated first in «Види нарахувань».
  2. A tariff cannot be deleted. A tariff created by mistake is switched off by an empty plot list or by excluding all
     plots (until a delete or archive exists).
  3. HEIC photos from an iPhone are accepted in Safari only. Otherwise set iPhone «Налаштування → Камера → Формати →
     Найсумісніший».
  4. Gmail needs an app password for the cooperative mailbox.
  5. The two sheets of «Нарахування по місяцях»: the first by the month a charge was booked, the second by the month
     of the service; a reading taken in October can carry August and September electricity.
  6. With auto-allocation on, a payment's free remainder goes to the oldest debts at once.
  7. Charges in a closed month have no «Сторнувати»; they are corrected by a recalculation.
- **Review:** the PR lists every page with its word count and its shots. The owner reads «Платежі і каса» and «Член
  кооперативу» before merging.

## 9. Out of scope

- a chat assistant or a language model;
- video;
- editing the help from the application;
- help for the platform operator screens (planned later as its own feature);
- languages other than `uk` and `en`.

## 10. Acceptance criteria

- [ ] Every route of `App.tsx` and every menu item has a help page in `uk` and `en` with all template sections and at
      least one screenshot. The check of section 7 passes and fails as described (a test for each failure case).
- [ ] «?» opens the matching page on every screen. A signed-in member sees only the member pages and the bill pages of
      «Як система рахує», in the contents, search and links. The electrician sees only the readings and meters pages.
      The chairman sees all. A direct link to a hidden page shows «Цей розділ вам недоступний» and no content.
- [ ] Search finds a page by a word from its text and by a button name, with one typo, in both languages, with no
      network request.
- [ ] `/help` works without signing in, makes no API request (e2e checks the network), has titles, descriptions and
      canonical URLs, and is indexable. The stand flag makes everything `noindex`.
- [ ] `make guide-shots` captures every listed shot in both languages, and `make guide` builds both board guide PDFs from
      `docs/help/`. The board guide content of the old HTML is all present (a checklist in the PR maps each old section
      to its new page).
- [ ] Button names in the help come from the i18n catalog.
- [ ] `make check` is green. e2e:
      - the member opens «?» on «Мій рахунок» (`@smoke`);
      - the treasurer searches «сторнувати платіж» and opens the page;
      - an anonymous visitor reads `/help/payments-enter`.

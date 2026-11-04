# Functional Specification: Help inside the application — per screen, per role, public

- **Topic:** Every screen of the application gets a help page that explains every button, field and option on it, with a
  worked example on the demo numbers and screenshots. The help opens from a «?» button on the screen, shows each person
  only the pages for the screens they can use, is also readable by anyone without signing in, and the board guide PDF
  is built from the same text.
- **Status:** Implemented — pending manual check
- **Author:** Alex Usov
- **Source:** `docs/features/019-in-app-help.md`

---

## 1. Overview and Rationale (The "Why")

Today the explanation of the system lives in a long PDF, the board guide. A treasurer who stands in front of the
payment screen and wonders what «Сторнувати» does has to leave the application, open the PDF and search through it.
Members have no help at all. The board of another cooperative that considers joining cannot read anything without an
account. And every time a screen changes, the PDF drifts a little further from what people see.

This feature puts the help where the question arises:

- a **«?» button** on every screen opens the page about that screen in a side panel;
- a **help section** with a table of contents and search;
- each person sees **only the pages for the screens they can open**, so a member is not buried in board screens;
- the same help is **public**, without signing in, so it can be read and found by search engines;
- the **board guide PDF is built from the same pages**, so the PDF and the help never differ.

The help is static text written by people. There is no chat assistant and no language model: a model would cost money,
answer slowly and could state wrong things about money with confidence (decided with the owner on 04.11.2026).

**Success looks like:** the treasurer on «Платежі» presses «?», reads what «Сторнувати» does and how a wrong payment is
corrected, without leaving the screen. A member on «Мій рахунок» presses «?» and reads how their bill is calculated. The
chairman of a neighbouring cooperative opens the public help from a link and reads how payments are allocated. A
renamed button that the help still quotes under its old name fails the build.

---

## 2. Functional Requirements (The "What")

Texts quoted in «» are the Ukrainian interface texts; the English interface shows their English equivalents. The help
always shows exactly one language, the one selected in the interface.

### 2.1 Help pages and their content

Every screen of the application has a help page in Ukrainian and in English. Where two addresses are one screen (for
example, a form for a new plot and for editing a plot), one page covers both. Pages are grouped in sections, in this
order: «Початок роботи», «Ділянки і члени», «Тарифи і нарахування», «Лічильники», «Платежі і каса», «Документи і
розсилка», «Звіти», «Налаштування», «Як система рахує», «Член кооперативу».

Every page about a screen has the same parts, in this order:

1. **What the screen is for** — one or two sentences.
2. **Who sees it** — the role names and, in plain words, the right that opens it.
3. **Every element** — each button, field, filter, tab and switch, in screen order: what it does, when to use it, what
   happens after (including what cannot be undone and how to correct it), and each error message it can show with what
   to do about it.
4. **Worked example** — step by step with the numbers the demo cooperative shows.
5. **Screenshots** — the screen, and the dialog or result of its main action, taken from the demo cooperative.
6. **Related pages** — links.

Each page covers the screen as the chairman sees it (the user with every right), and describes the member's view
separately where it differs.

Button and field names in the help are always the current names from the interface. When a name changes in the
interface, the help shows the new name; a help page that quotes a name the interface no longer has fails the build.

The text is written for people who are not technical: short sentences, no developer words. The English pages are a
faithful translation with the same structure and the same screenshots (taken in the English interface).

Everything written in the current board guide (both parts: working in the system, and how the system calculates) moves
into these pages; nothing is lost. Part II becomes the section «Як система рахує».

The following traps get a «Важливо» box on the page that explains the screen where they happen:

1. Two tariffs of one charge type cannot cover the same plots. The monthly water fee is charge type «Вода» (a fixed fee
   per plot, only connected plots pay); the well pump's electricity is «Спільні споживачі» with the split «Підключені
   ділянки». The water type may need to be switched on first in «Види нарахувань».
2. A tariff cannot be deleted. A tariff created by mistake is switched off by an empty plot list or by excluding every
   plot.
3. iPhone HEIC photos are accepted only in Safari. Otherwise set on the iPhone «Налаштування → Камера → Формати →
   Найсумісніший».
4. Gmail needs an app password for the cooperative mailbox.
5. The two sheets of «Нарахування по місяцях»: the first by the month a charge was booked, the second by the month of
   the service; a reading taken in October can carry August and September electricity.
6. With automatic allocation on, the free remainder of a payment goes to the oldest debts at once.
7. Charges in a closed month have no «Сторнувати»; they are corrected by a recalculation.

The help never mentions other cooperatives or the platform operator, and does not cover the operator's screens.

- **Acceptance Criteria:**
  - [x] When the chairman opens the contents of the help, then every screen reachable from the menu and every other
        screen of the application has its page, in both languages. — _`helpContent.test.ts` passes: every route of `App.tsx` and every menu item has a page in uk and en_
  - [x] When any help page is opened, then it shows the six parts in the order above and at least one screenshot. — _`helpContent.test.ts` (MISSING_SECTION, SCREEN_WITHOUT_SHOT, MISSING_SHOT: zero issues); 70 shots per language_
  - [x] When a reader opens the page of a screen where one of the seven traps happens, then the trap is shown in a
        «Важливо» box. — _traps 1–7 checked on their pages by the content reviews (review-log, slices 7–9)_
  - [x] When a reader compares the old board guide with the help, then every section of the old guide is found on a
        help page (a checklist in the pull request maps each old section to its page). — _`migration-map.md`: every heading of the old guide mapped, verified in the close-out review_
  - [x] When a button is renamed in the interface and the help is rebuilt, then the help shows the new name. — _`{{ui:}}` placeholders resolved from the catalogs (`parse.test.ts`), UNKNOWN_UI_KEY fails the check (`check.test.ts`)_

### 2.2 Keeping the help complete

The build fails, naming the problem, when:

- a screen of the application has no help page;
- a menu item has no help page;
- a help page refers to a screen that no longer exists;
- a help page shows a screenshot that does not exist;
- a page exists in one language and not in the other;
- a page lacks one of the six parts;
- a page quotes a button or field name the interface does not have.

- **Acceptance Criteria:**
  - [x] When every screen and menu item has a complete page in both languages, then the check passes. — _`helpContent.test.ts` green_
  - [x] When a screen is added without a help page, then the check fails and names the screen. — _`check.test.ts` ROUTE_WITHOUT_PAGE_
  - [x] When a menu item has no help page, then the check fails and names the menu item. — _`check.test.ts` MENU_WITHOUT_PAGE_
  - [x] When a help page refers to a screen that was removed, then the check fails and names the page. — _`check.test.ts` UNKNOWN_ROUTE_
  - [x] When a help page refers to a missing screenshot, then the check fails and names the page and the screenshot. — _`check.test.ts` MISSING_SHOT_
  - [x] When a page exists only in Ukrainian, then the check fails and names the page and the missing language. — _`check.test.ts` MISSING_TRANSLATION_
  - [x] When a page lacks the «Worked example» part, then the check fails and names the page and the part. — _`check.test.ts` MISSING_SECTION_
  - [x] When a page quotes a button name that no longer exists, then the check fails and names the page and the name. — _`parse.test.ts` / `check.test.ts` UNKNOWN_UI_KEY_

### 2.3 The «?» button and the side panel

Every screen has a «?» button in its header. Pressing it opens the help page of that screen in a panel on the side of
the screen; the screen stays where it was. On a phone the panel opens over the whole screen. The panel has «Відкрити
повністю», which opens the same page in the help section, and a button to close it.

- **Acceptance Criteria:**
  - [x] Given the treasurer is on «Платежі», when they press «?», then a side panel opens with the help page about the
        payments screen and the payments list stays visible behind it. — _e2e `help.spec.ts` §2.3 #1 passed_
  - [x] Given the member is on «Мій рахунок», when they press «?», then the panel shows the page about «Мій рахунок». — _e2e `help.spec.ts` §2.3 #2 (@smoke) passed_
  - [x] Given the panel is open, when the user presses «Відкрити повністю», then the same page opens in the help section. — _e2e §2.3 #2 passed_
  - [x] Given a phone-sized screen, when the user presses «?», then the help covers the whole screen and can be closed. — _e2e §2.3 #4 passed (390 px)_
  - [x] When the user presses «?» on any screen of the application, then the page about that screen opens. — _`pageForPath.test.ts`, `appRoutePaths.test.ts`, `helpContent.test.ts` (every route has a page); e2e §2.3 #5 negative passed_

### 2.4 The help section

The help section shows:

- the **contents**: pages grouped by section in the order of 2.1;
- a **search** field over all pages;
- the **page view**, with a list of the page's headings that stays visible while the page scrolls and jumps to a heading
  when clicked.

- **Acceptance Criteria:**
  - [x] When the user opens the help section, then they see the contents grouped by section in the order of 2.1. — _e2e §2.4 #1 passed_
  - [x] When the user opens a long page and scrolls, then the list of its headings stays visible, and when they click a
        heading, then the page jumps to it. — _`HelpPageView.test.tsx` passed_

### 2.5 Search

Search looks through page titles, headings and text, in the current interface language. It finds a word with one
letter missing, extra or wrong. It works without the internet once the application is open and makes no request to the
server. A search with no result shows «Нічого не знайдено».

- **Acceptance Criteria:**
  - [x] When the treasurer searches «сторнувати платіж», then the page about a payment is among the results and opens on
        click. — _e2e §2.5 #1 passed_
  - [x] When the user searches a word from a page's text with one wrong letter, then that page is among the results. — _e2e §2.5 #2 passed; `searchIndex.test.ts`_
  - [x] When the user searches a button name, then the page explaining that button is among the results. — _e2e §2.5 #3 passed_
  - [x] Given the interface is in English, when the user searches an English word, then English pages are found. — _e2e §2.5 #4 passed_
  - [x] When the user searches a word that appears nowhere, then they see «Нічого не знайдено». — _e2e §2.5 #5 passed_
  - [x] When the user searches, then no request leaves the browser. — _e2e §2.5 #6 passed (no `/api/` request)_

### 2.6 What each signed-in user sees

The help shows a signed-in user only the pages for the screens they can open:

- a page about a screen is shown when the user has any of the rights that open that screen; a page for everyone is
  shown to every signed-in user;
- the pages of «Член кооперативу» are shown only to a person who currently owns a plot;
- in «Як система рахує», the pages that explain a member's own bill (tariffs, readings and electricity, losses and
  common consumers, recalculation, allocation of payments) are shown to everyone signed in; the other pages of that
  section follow the rights of the screens they explain.

This applies to the contents, the search results, the related links and the «?» panel. A link inside a page to a page
the user cannot see is shown as plain text, not a link. Opening the address of such a page directly shows «Цей розділ
вам недоступний» with a link to the contents, and nothing of the page.

This is a filter for clarity, not a secret: the same pages are public (2.7).

- **Acceptance Criteria:**
  - [x] Given the chairman is signed in, when they open the contents, then every page is listed. — _e2e §2.6 #1 passed (64 pages) after verify fix `014b702`_
  - [x] Given the member is signed in, when they open the contents, then they see only the pages for everyone, the pages
        of «Член кооперативу» and the bill pages of «Як система рахує». — _e2e §2.6 #2 passed_
  - [?] Given the electrician is signed in, when they open the contents, then of the board screens they see only the
        pages about readings and meters. — _e2e §2.6 #3 written; on the local dev database the demo role «Електрик» carries extra permissions left by earlier runs, so the spec fails on its precondition; `helpVisibility.test.ts` covers the rule. Needs a run on a fresh database (CI full e2e or `make e2e`)_
  - [x] Given the member is signed in, when they search «сторнувати», then the page about a payment is not among the
        results. — _e2e §2.6 #4 passed_
  - [x] Given the member reads a page that links to a board page, when they look at that link, then it is plain text and
        cannot be clicked. — _`HelpPageView.test.tsx` hidden-link case passed_
  - [x] Given the member is signed in, when they open the address of the payment-entry help page directly, then they see
        «Цей розділ вам недоступний» with a link to the contents and no text of that page. — _e2e §2.6 #5 passed_

### 2.7 Public help

The help section opens without signing in. A visitor sees every page of the board guide and the member pages, with
the contents in the order of the guide. The header has «Увійти», which starts the normal sign-in, and the language
switch. The public help shows no cooperative data, only the screenshots of the fictional demo cooperative, and makes no
request to the server.

Each public page has a title and a short description for search engines, its own permanent address, and a preview image
(its first screenshot) for links shared in messengers. The help section may be indexed by search engines; the rest of
the application is not.

A test stand sends "do not index" for everything, the help included, so it does not compete with the real site.

- **Acceptance Criteria:**
  - [x] Given a visitor is not signed in, when they open the address of the payment-entry help page, then they read it
        in full with its screenshots, without a sign-in screen. — _e2e §2.7 #1 passed_
  - [x] Given a visitor reads the public help, when they look at the page header, then they see «Увійти», and when they
        press it, then the sign-in starts. — _e2e §2.7 #2 passed (reaches the Keycloak sign-in)_
  - [x] When a visitor opens and searches the public help, then no request is made to the application's server. — _e2e §2.7 #3 passed; `PublicHelpApp.test.tsx`_
  - [x] When a public help page is opened, then the browser tab shows its title, and the page carries a description, a
        permanent address and a preview image. — _e2e §2.7 #4 passed; `vitePlugin.test.ts` (shells)_
  - [x] When a search engine reads a help page, then it is allowed to index it, and when it reads any other screen of
        the application, then it is told not to index it. — _e2e §2.7 #5 passed (`index, follow` on help, `noindex` on `/payments`)_
  - [?] Given the test stand, when any page is opened there, then the reply says "do not index". — _`infra/stand/` is not tracked in the repository (excluded locally); the `NADEL_NOINDEX` change exists only in the owner's working copy and was validated with `caddy validate` only_

### 2.8 Screenshots

The screenshots are taken from the running application with the demo cooperative, for both languages, by one command;
another command takes the screenshots of one page. Each page lists its own screenshots: the screen, the demo user, an
optional action (open a dialog, fill a field) and the size (computer or phone). A screenshot that cannot be taken stops
the command and names the page. Screenshots are taken only from the demo cooperative: the command refuses to run against
any other cooperative's data.

- **Acceptance Criteria:**
  - [x] Given the application runs with the demo data, when the owner runs the screenshot command, then every listed
        screenshot is taken in both languages, for the help and for the PDF. — _`capture-screens.mjs` full run exit 0, 70 PNGs per language_
  - [x] When the owner runs the command for one page, then only that page's screenshots are taken. — _`--page announcement-card` re-run took only that page_
  - [x] When a listed screenshot cannot be taken, then the command stops and names the page. — _first run stopped with `announcement-card: … locator.click timeout` and exit 1_
  - [?] Given the application holds a cooperative other than the demo one, when the command runs, then it refuses with
        a message and takes nothing. — _demo guard reviewed in code (`capture-screens.mjs` guardDemo); not exercised against a non-demo database_

### 2.9 The board guide PDF

The board guide PDF, in Ukrainian and English, is built from the help pages in the order of the contents, with the
screenshots, a table of contents and page numbers. The title page shows the version (the release tag or the date of
the last change). The owner manual PDF and the Telegram leaflet are built as now.

- **Acceptance Criteria:**
  - [?] When the owner builds the guides, then the board guide PDF in both languages contains every help page in the
        order of the contents, with screenshots, a table of contents and page numbers. — _`make guide` built `board-guide.uk.pdf` (~141 pages, 70 images) and `.en.pdf`; contents order, TOC and page numbers by code; layout not visually inspected (no PDF renderer here)_
  - [?] When the owner opens the PDF, then the title page shows the version. — _version from `git describe`/commit date by code; not visually inspected_
  - [x] When the owner builds the guides, then the owner manual PDF and the leaflet are still produced. — _`make guide` printed `built owner-manual.uk/en.pdf` and `telegram-leaflet.uk.pdf`_

---

## 3. Scope and Boundaries

### In-Scope

- A help page for every screen of the application in Ukrainian and English, with the content of the current board guide.
- The «?» button and side panel, the help section with contents and search.
- Showing each signed-in user only their pages; the public help without signing in, ready for search engines.
- The "do not index" switch for the test stand.
- Screenshot commands; the board guide PDF built from the help pages.
- The build check that keeps the help complete.
- End-to-end checks: the member opens «?» on «Мій рахунок» (smoke set); the treasurer searches «сторнувати платіж» and
  opens the page; an anonymous visitor reads the payment-entry page.

### Out-of-Scope

- A chat assistant or a language model on top of the help (may come later).
- Video.
- Editing the help from the application.
- Help for the platform operator's screens.
- Languages other than Ukrainian and English.
- The owner manual and the Telegram leaflet do not move into the help; the owner manual stays a PDF only and is not
  public.

---

## 4. Assumptions & Decisions

- **Static help, the PDFs stay, the help is public** — decided with the owner on 04.11.2026 (requirement).
- **Tests deferred to the end** — the user's instruction for this run: every task is implemented first, with no test
  runs between tasks or slices; the whole feature is verified once at the end.
- AUTO: **"Owns a plot"** means the person is a current owner of at least one plot of the cooperative; former owners do
  not see the member pages. The member pages are also shown to anyone who may view the members (the chairman always
  can), so the chairman sees every page as 2.6 requires even without a plot of their own (decided in verification).
- AUTO: **The electrician** sees the pages for everyone signed in, the bill pages of «Як система рахує», the member
  pages if they own a plot, and, of the board screens, only the readings and meters pages. "Only the readings and meters
  pages" in the requirement is read as "of the board pages".
- AUTO: **Pages for everyone** («Початок роботи»: signing in, the menu, language and theme, the help itself) are shown
  to every signed-in user.
- AUTO: **A signed-in platform operator** has no cooperative and sees the same pages as any signed-in user with no
  rights (the pages for everyone and the bill pages).
- AUTO: **A visitor who is not signed in** sees every help page (board and member pages). The help section shows the
  language switch; the language follows the visitor’s previous choice, else the browser language, else Ukrainian — the
  same rule as the rest of the application (amended in verification).
- AUTO: **A screen without its own page** («Сторінку не знайдено») shows no «?» button.
- AUTO: **Search ranking** puts title matches above heading matches above text matches; at most 20 results are shown.
- AUTO: **The old board guide document** is replaced by the help pages once its content has moved; the board guide PDF
  keeps its file name.
- AUTO: **The stand switch** is a setting of the test stand's web server, off by default; the production server keeps
  indexing the help.
- AUTO: **The rule for future features** (a feature that adds or changes a screen updates its help page and screenshots
  in the same pull request) is proposed to the owner in the pull request; the owner commits it to the project rules.
- AUTO: **Screenshots** are taken from the running demo application at the end of the work; until then, a page may
  refer to a screenshot that is still to be taken only if the build check is satisfied when the work is finished.

---

## Change Log

- 2026-11-04 — created by `/feature-pipeline` from `docs/features/019-in-app-help.md`.

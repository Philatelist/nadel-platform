# Guides

Two guides, each in Ukrainian and English. They are written for people, not for the pipeline, and are the only
documents in the repository that are not English-only (`CLAUDE.md`, "Guides").

| Guide | Reader | Source | PDFs |
|---|---|---|---|
| Board guide: every screen and button, how the system calculates, worked examples | the chairman, the treasurer, the electrician | the help pages `docs/help/<lang>/<id>.md` (the in-app help, see `docs/help/README.md`) | `pdf/board-guide.uk.pdf`, `pdf/board-guide.en.pdf` |
| Platform owner's manual: components, local run, Keycloak, creating cooperatives, the operator, every screen and button, security, deployment, development, troubleshooting, decision log | the owner | `uk/owner-manual.html`, `en/owner-manual.html` | `pdf/owner-manual.{uk,en}.pdf` |

A one-page leaflet for members, "Як отримувати рахунки в Telegram" (`uk/telegram-leaflet.html`, Ukrainian only), is
built to `pdf/telegram-leaflet.uk.pdf` by the same command, without the running footer of the guides.

The board guide has no HTML source of its own: the help pages are its single source. The PDF is built from the
compiled help (title page with the version, contents by section, one chapter per page, in the order of the in-app
contents). Edit the help pages, not the PDF. The owner manual and the leaflet stay hand-written HTML.

The PDFs in `pdf/` are built from these sources and committed, so they can be opened on GitHub or sent as files.

## Building

```bash
make up                       # in another terminal: the app with the demo data
make guide-shots              # screenshots of every help page, both languages, into web/public/help/img/<lang>/
make help-shots ID=<page id>  # the same for one help page, for example ID=payments-enter
make guide                    # PDFs into pdf/
```

`make guide` works without the app: a screenshot that is missing is shown as a placeholder. The screenshots
themselves are strict: if one cannot be taken, the script prints `<page id>: <shot>: <reason>` and exits with 1; it
never skips a shot or writes a placeholder image (the help build fails on a missing screenshot file anyway).

## Screenshots

- The list of shots of a page is the `shots` field in its front matter (`docs/help/README.md` describes the fields).
  The Ukrainian page's list is taken in the Ukrainian interface, the English page's list in the English one, so the
  `steps` texts (button, tab and field names) are in the language of the page.
- Output: `web/public/help/img/<lang>/<name>.png`, served by the application and used by the PDF.
  The retired HTML board guide and its shots (`docs/guide/img/`) are gone; the owner manual uses no screenshots.
- Shots are taken with the demo users, in the light theme, at 1280×860 (`viewport: phone` gives 360×740, `phone-ios` the same with the Safari on iPhone user agent).
- Demo guard: before the first shot the script signs in as the chairman and reads `GET /api/me`. If the cooperative is
  not the demo cooperative of `backend/src/main/resources/db/demo/R__demo_data.sql`
  (`11111111-1111-1111-1111-111111111111`), it takes nothing and exits with 1, so real data can never end up in a guide.
- Read-only: the script only opens pages, previews and dialogs; it never saves anything.
- `GUIDE_BASE_URL` (default `http://localhost:5173`), `GUIDE_DEMO_PASSWORD`, `GUIDE_CHROMIUM` and
  `GUIDE_NODE_MODULES` override the defaults.

## Keeping them current

A feature that changes what a user sees or how money is calculated updates the help pages in both languages in the same
pull request (and with them the board guide), the owner manual in `uk/` and `en/` when the platform's running changes,
and the PDFs (`make guide-shots && make guide`).

Layout: `guide.css` (A4 print styles in the colours of the app), `scripts/build-pdf.mjs`,
`scripts/capture-screens.mjs`, `scripts/helpPages.mjs` (loads the help compiler `web/help/` through Vite's module runner,
shared by both scripts). Fonts come from the `@fontsource` packages installed by `make install`.

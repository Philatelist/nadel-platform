# Guides

Two guides, each in Ukrainian and English. They are written for people, not for the pipeline, and are the only
documents in the repository that are not English-only (`CLAUDE.md`, "Guides").

| Guide | Reader | Ukrainian | English |
|---|---|---|---|
| Board guide: part I — working in the system, part II — how the system calculates (rules and worked examples) | the chairman, the treasurer, the electrician | `uk/board-guide.html` | `en/board-guide.html` |
| Platform owner's manual: components, local run, Keycloak, creating cooperatives, the operator, every screen and button, security, deployment, development, troubleshooting, decision log | the owner | `uk/owner-manual.html` | `en/owner-manual.html` |

A one-page leaflet for members, "Як отримувати рахунки в Telegram" (`uk/telegram-leaflet.html`, Ukrainian only), is
built to `pdf/telegram-leaflet.uk.pdf` by the same command, without the running footer of the guides.

The PDFs in `pdf/` are built from these sources and committed, so they can be opened on GitHub or sent as files.

## Building

```bash
make up            # in another terminal: the app with the demo data
make guide-shots   # screenshots of the running app, both languages, into img/uk and img/en
make guide         # PDFs into pdf/
```

`make guide` works without the app: a screen without a screenshot is shown as a placeholder. Screenshots are taken
with the demo users, in the light theme, at 1280×860 (phone screens at 390×844).

## Keeping them current

A feature that changes what a user sees or how money is calculated updates both languages of the affected guide in the
same pull request: the text in `uk/` and `en/`, the screenshot list in `scripts/capture-screens.mjs` for a new screen,
and the PDFs (`make guide-shots && make guide`). A planned part is marked with the "planned" tag and becomes "works" in
the feature that delivers it.

Layout: `guide.css` (A4 print styles in the colours of the app), `scripts/build-pdf.mjs`,
`scripts/capture-screens.mjs`. Fonts come from `web/node_modules/@fontsource`.

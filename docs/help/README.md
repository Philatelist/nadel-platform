# Help content

The in-app help is written here as Markdown, one file per page and language: `docs/help/<lang>/<id>.md`
(`lang` is `uk` or `en`). The files are compiled at build time by `web/help/` and served to the application as the
virtual modules `virtual:help/uk` and `virtual:help/en`. Nothing here is read at run time.

A page exists in both languages with the same `id` (the file name) and the same structure. The Ukrainian page is the
source; the English page is a faithful translation with the same `shots` names.

## Front matter

Every page starts with a YAML block.

| Field | Meaning |
|---|---|
| `id` | Equals the file name without `.md`. |
| `title` | Page title; also the tab title. |
| `description` | One sentence for search engines. |
| `kind` | `screen` (explains one screen, six-part template, at least one shot) or `topic` (free structure). |
| `routes` | React Router patterns the page explains, for example `/payments/new`. Empty for topics. |
| `permissions` | Any of these `Permission` values, or `member` (owns a plot). Empty means every signed-in user. |
| `section` | `getting-started`, `plots-members`, `tariffs-charges`, `meters`, `payments-cash`, `documents-delivery`, `reports`, `settings`, `how-it-calculates`, `member`: the order of the contents. |
| `order` | Integer; the order inside the section. |
| `shots` | Screenshots to capture: `{ name, user, path, viewport?, open?, steps?, element? }`. Same step vocabulary as `docs/guide/scripts/capture-screens.mjs`; `steps` text is in the page's language. |

## The six parts of a `screen` page

A `screen` page has exactly these `##` headings, in this order. Extra `###` headings inside them are free.

| Ukrainian | English |
|---|---|
| Для чого цей екран | What the screen is for |
| Хто бачить | Who sees it |
| Елементи екрана | Every element |
| Приклад | Worked example |
| Знімки екрана | Screenshots |
| Пов’язані сторінки | Related pages |

## Markup

- Screenshot: `![alt](shot:<name>)`. `<name>` is one of the page's `shots`; the image is `/help/img/<lang>/<name>.png`.
- Link to another page: `[text](help:<id>)`, optionally `[text](help:<id>#anchor)`. Anchors are the heading text in
  lower case with dashes.
- Interface names: `{{ui:payments.reverse.action}}`. The dot path points into the i18n catalog (`packages/shared/src/i18n`);
  the page shows the string of its own language, styled as an interface label. An unknown path is an error, so a renamed
  button cannot stay quoted wrongly. Never type a button name by hand.
- «Важливо» box: a blockquote whose first line is `[!important]`.

  ```
  > [!important]
  > The text of the warning.
  ```

- Tables, lists, bold, italic and inline code are plain Markdown. Raw HTML is not allowed.

## Style

Short sentences, no developer words. Describe the screen as the chairman sees it, and the member's view separately where
it differs. Never mention other cooperatives or the platform operator.

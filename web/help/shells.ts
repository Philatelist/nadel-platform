import type { HelpBundlePage } from './types.ts';

export interface ShellInput {
  /** The built `index.html`. */
  template: string;
  pages: readonly Pick<HelpBundlePage, 'id' | 'title' | 'description' | 'shots'>[];
  /** Public origin without a trailing slash. */
  siteUrl: string;
  contents: { title: string; description: string };
  lang: string;
}

export interface Shell {
  /** Path inside `dist`, e.g. `help/payments-enter/index.html`. */
  file: string;
  html: string;
}

export function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

interface Head {
  title: string;
  description: string;
  url: string;
  image?: string;
}

function shell(template: string, lang: string, head: Head): string {
  const e = escapeHtml;
  const tags = [
    `<meta name="description" content="${e(head.description)}" />`,
    `<link rel="canonical" href="${e(head.url)}" />`,
    `<meta property="og:title" content="${e(head.title)}" />`,
    `<meta property="og:description" content="${e(head.description)}" />`,
    `<meta property="og:type" content="article" />`,
    `<meta property="og:url" content="${e(head.url)}" />`,
    ...(head.image ? [`<meta property="og:image" content="${e(head.image)}" />`] : []),
  ].join('\n    ');
  return template
    .replace(/<html lang="[^"]*"/, () => `<html lang="${e(lang)}"`)
    .replace(/<title>[^<]*<\/title>/, () => `<title>${e(head.title)}</title>`)
    .replace(/<meta name="robots"[^>]*>/, () => '<meta name="robots" content="index, follow" />')
    .replace('</head>', () => `  ${tags}\n  </head>`);
}

/** The static `/help` shells: the built shell with the head of each page, so crawlers and messengers need no JavaScript. */
export function buildHelpShells({ template, pages, siteUrl, contents, lang }: ShellInput): Shell[] {
  const result: Shell[] = [
    {
      file: 'help/index.html',
      html: shell(template, lang, { ...contents, url: `${siteUrl}/help` }),
    },
  ];
  for (const page of pages) {
    const first = page.shots[0];
    result.push({
      file: `help/${page.id}/index.html`,
      html: shell(template, lang, {
        title: page.title,
        description: page.description,
        url: `${siteUrl}/help/${page.id}`,
        image: first ? `${siteUrl}/help/img/uk/${first.name}.png` : undefined,
      }),
    });
  }
  return result;
}

import { describe, expect, it } from 'vitest';
import { buildHelpShells, escapeHtml } from './shells.ts';

const template = `<!doctype html>
<html lang="en">
  <head>
    <meta name="robots" content="noindex" />
    <title>Nadel</title>
  </head>
  <body></body>
</html>`;

const shot = { name: 'payment-form', user: 'chairman', path: '/payments/new' };
const input = {
  template,
  siteUrl: 'https://nadel.example',
  lang: 'uk',
  contents: { title: 'Довідка', description: 'Зміст довідки' },
  pages: [
    { id: 'payments-enter', title: 'Внесення "оплати"', description: 'A & B <b>', shots: [shot] },
    { id: 'plain', title: 'Без знімка', description: 'Опис', shots: [] },
  ],
};

describe('buildHelpShells', () => {
  const shells = buildHelpShells(input);
  const byFile = (file: string): string => shells.find((shell) => shell.file === file)?.html ?? '';

  it('emits the contents and one shell per page', () => {
    expect(shells.map((shell) => shell.file)).toEqual([
      'help/index.html',
      'help/payments-enter/index.html',
      'help/plain/index.html',
    ]);
  });

  it('carries the head tags of a page', () => {
    const html = byFile('help/payments-enter/index.html');
    expect(html).toContain('<html lang="uk"');
    expect(html).toContain('<meta name="robots" content="index, follow" />');
    expect(html).not.toContain('noindex');
    expect(html).toContain('<link rel="canonical" href="https://nadel.example/help/payments-enter" />');
    expect(html).toContain('<meta property="og:type" content="article" />');
    expect(html).toContain(
      '<meta property="og:image" content="https://nadel.example/help/img/uk/payment-form.png" />',
    );
    expect(html).toContain('<meta name="description" content="A &amp; B &lt;b&gt;" />');
  });

  it('escapes injected values', () => {
    const html = byFile('help/payments-enter/index.html');
    expect(html).toContain('<title>Внесення &quot;оплати&quot;</title>');
    expect(html).toContain('<meta property="og:title" content="Внесення &quot;оплати&quot;" />');
    expect(html).not.toContain('<b>');
  });

  it('omits og:image for a page without a shot and uses the contents address for the index', () => {
    expect(byFile('help/plain/index.html')).not.toContain('og:image');
    expect(byFile('help/index.html')).toContain('href="https://nadel.example/help"');
  });

  it('escapes the five special characters', () => {
    expect(escapeHtml(`&<>"'`)).toBe('&amp;&lt;&gt;&quot;&#39;');
  });
});

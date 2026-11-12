import type { Locale, Messages } from '@nadel/shared';
import MarkdownIt, { type Token } from 'markdown-it';
import { parse as parseYaml } from 'yaml';
import {
  HELP_SECTIONS,
  type HelpElementTag,
  type HelpHeading,
  type HelpIssue,
  type HelpKind,
  type HelpNode,
  type HelpPage,
  type HelpSection,
  type HelpShot,
} from './types';

export interface ParseInput {
  /** File name without extension; the front matter `id` must equal it. */
  id: string;
  locale: Locale;
  source: string;
  /** Catalog of the page's language, for `{{ui:key.path}}`. */
  messages: Messages;
}

export interface ParseResult {
  /** `null` when the front matter is unusable; the issues say why. */
  page: HelpPage | null;
  issues: HelpIssue[];
}

const FRONT_MATTER = /^---\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/;
const UI_PLACEHOLDER = /\{\{ui:([^}\s]+)\}\}/g;
const IMPORTANT_MARKER = '[!important]';

const md = new MarkdownIt({ html: false, linkify: false, typographer: false });

const BLOCK_TAGS: Partial<Record<string, HelpElementTag>> = {
  bullet_list: 'ul',
  ordered_list: 'ol',
  list_item: 'li',
  blockquote: 'blockquote',
  table: 'table',
  thead: 'thead',
  tbody: 'tbody',
  tr: 'tr',
  th: 'th',
  td: 'td',
  strong: 'strong',
  em: 'em',
  paragraph: 'p',
};

/** Anchor for a heading: lower-case letters and digits of any alphabet joined by dashes. */
export function slugify(text: string): string {
  return (
    text
      .toLowerCase()
      .replace(/[^\p{L}\p{N}]+/gu, '-')
      .replace(/^-+|-+$/g, '') || 'section'
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function stringList(value: unknown): string[] | null {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || !value.every((v) => typeof v === 'string')) return null;
  return value as string[];
}

function parseShot(value: unknown): HelpShot | null {
  if (!isRecord(value)) return null;
  const { name, user, path, viewport, open, steps, element } = value;
  if (typeof name !== 'string' || typeof user !== 'string' || typeof path !== 'string') return null;
  if (viewport !== undefined && viewport !== 'desktop' && viewport !== 'phone' && viewport !== 'phone-ios')
    return null;
  if (open !== undefined && !isRecord(open)) return null;
  if (element !== undefined && !isRecord(element)) return null;
  if (steps !== undefined && !(Array.isArray(steps) && steps.every(isRecord))) return null;
  const shot: HelpShot = { name, user, path };
  if (viewport) shot.viewport = viewport;
  if (open) shot.open = open;
  if (steps) shot.steps = steps as Record<string, unknown>[];
  if (element) shot.element = element;
  return shot;
}

interface FrontMatter {
  title: string;
  description: string;
  kind: HelpKind;
  routes: string[];
  permissions: string[];
  section: HelpSection;
  order: number;
  shots: HelpShot[];
}

/** Returns the front matter, or the reasons it is unusable. */
function readFrontMatter(raw: unknown, id: string): FrontMatter | string[] {
  if (!isRecord(raw)) return ['front matter is missing or is not a mapping'];
  const problems: string[] = [];
  if (raw.id !== id) problems.push(`id "${String(raw.id)}" must equal the file name "${id}"`);
  if (typeof raw.title !== 'string' || raw.title.trim() === '') problems.push('title is required');
  if (typeof raw.description !== 'string' || raw.description.trim() === '')
    problems.push('description is required');
  if (raw.kind !== 'screen' && raw.kind !== 'topic') problems.push('kind must be "screen" or "topic"');
  const routes = stringList(raw.routes);
  if (!routes) problems.push('routes must be a list of strings');
  const permissions = stringList(raw.permissions);
  if (!permissions) problems.push('permissions must be a list of strings');
  if (typeof raw.section !== 'string' || !(HELP_SECTIONS as readonly string[]).includes(raw.section))
    problems.push(`section must be one of ${HELP_SECTIONS.join(', ')}`);
  if (typeof raw.order !== 'number' || !Number.isInteger(raw.order))
    problems.push('order must be an integer');
  const shotsRaw = raw.shots ?? [];
  const shots = Array.isArray(shotsRaw) ? shotsRaw.map(parseShot) : null;
  if (!shots || shots.some((s) => s === null))
    problems.push('shots must be a list of { name, user, path, viewport?, open?, steps?, element? }');
  if (problems.length > 0) return problems;
  return {
    title: raw.title as string,
    description: raw.description as string,
    kind: raw.kind as HelpKind,
    routes: routes as string[],
    permissions: permissions as string[],
    section: raw.section as HelpSection,
    order: raw.order as number,
    shots: shots as HelpShot[],
  };
}

function lookup(messages: Messages, path: string): string | null {
  let node: unknown = messages;
  for (const part of path.split('.')) {
    if (!isRecord(node) || !(part in node)) return null;
    node = node[part];
  }
  return typeof node === 'string' ? node : null;
}

interface BuildContext {
  id: string;
  locale: Locale;
  messages: Messages;
  issues: HelpIssue[];
  headings: HelpHeading[];
  usedAnchors: Map<string, number>;
}

function appendText(out: HelpNode[], text: string): void {
  if (text === '') return;
  const last = out[out.length - 1];
  if (last?.type === 'text') last.text += text;
  else out.push({ type: 'text', text });
}

/** Splits a text run on `{{ui:path}}` placeholders and resolves them from the catalog. */
function textNodes(text: string, ctx: BuildContext): HelpNode[] {
  const out: HelpNode[] = [];
  let last = 0;
  for (const match of text.matchAll(UI_PLACEHOLDER)) {
    appendText(out, text.slice(last, match.index));
    last = match.index + match[0].length;
    const key = match[1] as string;
    const resolved = lookup(ctx.messages, key);
    if (resolved === null) {
      ctx.issues.push({
        code: 'UNKNOWN_UI_KEY',
        pageId: ctx.id,
        locale: ctx.locale,
        detail: `{{ui:${key}}} is not a string in the ${ctx.locale} catalog`,
      });
      out.push({ type: 'ui', key, text: key });
    } else {
      out.push({ type: 'ui', key, text: resolved });
    }
  }
  appendText(out, text.slice(last));
  return out;
}

export function nodeText(nodes: readonly HelpNode[]): string {
  const parts: string[] = [];
  const walk = (list: readonly HelpNode[]): void => {
    for (const node of list) {
      switch (node.type) {
        case 'text':
          parts.push(node.text);
          break;
        case 'ui':
          parts.push(node.text);
          break;
        case 'br':
        case 'hr':
        case 'img':
          parts.push(' ');
          break;
        default:
          parts.push(' ');
          walk(node.children);
          parts.push(' ');
      }
    }
  };
  walk(nodes);
  return parts.join('').replace(/\s+/g, ' ').trim();
}

function linkNode(href: string, children: HelpNode[]): HelpNode {
  if (href.startsWith('help:')) {
    const [pageId = '', anchor] = href.slice('help:'.length).split('#');
    return anchor ? { type: 'link', pageId, anchor, children } : { type: 'link', pageId, children };
  }
  if (/^(https?:|mailto:)/i.test(href)) return { type: 'a', href, children };
  // Any other scheme is dropped: only the text is kept.
  return { type: 'strong', children: children.length > 0 ? children : [{ type: 'text', text: href }] };
}

function build(tokens: Token[], ctx: BuildContext): HelpNode[] {
  const root: HelpNode[] = [];
  const stack: { token: Token; children: HelpNode[] }[] = [];
  const current = (): HelpNode[] => stack[stack.length - 1]?.children ?? root;

  for (const token of tokens) {
    switch (token.type) {
      case 'inline':
        current().push(...build(token.children ?? [], ctx));
        break;
      case 'text':
        current().push(...textNodes(token.content, ctx));
        break;
      case 'softbreak':
        appendText(current(), ' ');
        break;
      case 'hardbreak':
        current().push({ type: 'br' });
        break;
      case 'hr':
        current().push({ type: 'hr' });
        break;
      case 'code_inline':
        current().push({ type: 'code', children: [{ type: 'text', text: token.content }] });
        break;
      case 'fence':
      case 'code_block':
        current().push({
          type: 'pre',
          children: [{ type: 'code', children: [{ type: 'text', text: token.content }] }],
        });
        break;
      case 'image': {
        const src = String(token.attrGet('src') ?? '');
        if (src.startsWith('shot:')) {
          current().push({ type: 'img', shot: src.slice('shot:'.length), alt: token.content });
        } else {
          appendText(current(), token.content);
        }
        break;
      }
      case 'link_open':
      case 'heading_open':
        stack.push({ token, children: [] });
        break;
      case 'link_close': {
        const frame = stack.pop();
        if (frame) current().push(linkNode(String(frame.token.attrGet('href') ?? ''), frame.children));
        break;
      }
      case 'heading_close': {
        const frame = stack.pop();
        if (!frame) break;
        const markup = frame.token.markup.length;
        const level = markup <= 2 ? 2 : markup === 3 ? 3 : 4;
        const text = nodeText(frame.children);
        const base = slugify(text);
        const seen = ctx.usedAnchors.get(base) ?? 0;
        ctx.usedAnchors.set(base, seen + 1);
        const id = seen === 0 ? base : `${base}-${seen + 1}`;
        if (level !== 4) ctx.headings.push({ level, id, text });
        current().push({ type: `h${level}` as 'h2' | 'h3' | 'h4', id, children: frame.children });
        break;
      }
      default: {
        if (token.nesting === 1) {
          stack.push({ token, children: [] });
        } else if (token.nesting === -1) {
          const frame = stack.pop();
          if (!frame) break;
          const tag = BLOCK_TAGS[frame.token.type.replace(/_open$/, '')];
          if (!tag) {
            current().push(...frame.children);
          } else if (frame.token.hidden) {
            current().push(...frame.children);
          } else if (tag === 'blockquote') {
            current().push(toCalloutOrQuote(frame.children));
          } else {
            current().push({ type: tag, children: frame.children });
          }
        }
      }
    }
  }
  return root;
}

/** A blockquote whose first line is `[!important]` becomes a callout. */
function toCalloutOrQuote(children: HelpNode[]): HelpNode {
  const first = children[0];
  const lead = first?.type === 'p' ? first.children[0] : undefined;
  if (first?.type !== 'p' || lead?.type !== 'text' || !lead.text.startsWith(IMPORTANT_MARKER)) {
    return { type: 'blockquote', children };
  }
  const rest = lead.text.slice(IMPORTANT_MARKER.length).trimStart();
  const paragraph: HelpNode[] =
    rest === '' ? first.children.slice(1) : [{ type: 'text', text: rest }, ...first.children.slice(1)];
  const body: HelpNode[] =
    paragraph.length > 0 ? [{ type: 'p', children: paragraph }, ...children.slice(1)] : children.slice(1);
  return { type: 'callout', children: body };
}

/** Parses one help page: front matter, body to the node tree, headings, plain text, placeholders. */
export function parsePage({ id, locale, source, messages }: ParseInput): ParseResult {
  const issues: HelpIssue[] = [];
  const bad = (detail: string): ParseResult => {
    issues.push({ code: 'BAD_FRONT_MATTER', pageId: id, locale, detail });
    return { page: null, issues };
  };

  const match = FRONT_MATTER.exec(source);
  if (!match) return bad('front matter is missing');
  let raw: unknown;
  try {
    raw = parseYaml(match[1] ?? '');
  } catch (error) {
    return bad(`front matter is not valid YAML: ${error instanceof Error ? error.message : String(error)}`);
  }
  const front = readFrontMatter(raw, id);
  if (Array.isArray(front)) return bad(front.join('; '));

  const ctx: BuildContext = { id, locale, messages, issues, headings: [], usedAnchors: new Map() };
  const body = build(md.parse(source.slice(match[0].length), {}), ctx);
  return {
    page: { id, locale, ...front, headings: ctx.headings, body, text: nodeText(body) },
    issues,
  };
}

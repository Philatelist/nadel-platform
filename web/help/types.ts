import type { Locale } from '@nadel/shared';

/** Sections of the help contents, in display order. */
export const HELP_SECTIONS = [
  'getting-started',
  'plots-members',
  'tariffs-charges',
  'meters',
  'payments-cash',
  'documents-delivery',
  'reports',
  'settings',
  'how-it-calculates',
  'member',
] as const;

export type HelpSection = (typeof HELP_SECTIONS)[number];
export type HelpKind = 'screen' | 'topic';
export type HelpViewport = 'desktop' | 'phone' | 'phone-ios';

/**
 * One screenshot to capture. `open`, `steps` and `element` use the step vocabulary of
 * `docs/guide/scripts/capture-screens.mjs`; they are carried through untouched.
 */
export interface HelpShot {
  name: string;
  user: string;
  path: string;
  viewport?: HelpViewport;
  open?: Record<string, unknown>;
  steps?: Record<string, unknown>[];
  element?: Record<string, unknown>;
}

export type HelpElementTag =
  | 'p'
  | 'h2'
  | 'h3'
  | 'h4'
  | 'ul'
  | 'ol'
  | 'li'
  | 'blockquote'
  | 'table'
  | 'thead'
  | 'tbody'
  | 'tr'
  | 'th'
  | 'td'
  | 'strong'
  | 'em'
  | 'code'
  | 'pre';

/** The JSON node tree a page body is compiled into. Rendered by React elements, never as raw HTML. */
export type HelpNode =
  | { type: 'text'; text: string }
  | { type: 'br' }
  | { type: 'hr' }
  | { type: HelpElementTag; children: HelpNode[]; id?: string }
  | { type: 'img'; shot: string; alt: string }
  | { type: 'link'; pageId: string; anchor?: string; children: HelpNode[] }
  | { type: 'a'; href: string; children: HelpNode[] }
  | { type: 'ui'; key: string; text: string }
  | { type: 'callout'; children: HelpNode[] };

export interface HelpHeading {
  level: 2 | 3;
  id: string;
  text: string;
}

export interface HelpPage {
  id: string;
  locale: Locale;
  title: string;
  description: string;
  kind: HelpKind;
  routes: string[];
  permissions: string[];
  section: HelpSection;
  order: number;
  shots: HelpShot[];
  headings: HelpHeading[];
  body: HelpNode[];
  /** Plain text of the body, for the search index. */
  text: string;
}

/** What the client receives: the pages without the plain text, and the serialized search index. */
export type HelpBundlePage = Omit<HelpPage, 'text'>;

export interface HelpBundle {
  locale: Locale;
  pages: HelpBundlePage[];
  /** Serialized MiniSearch index; `null` until the search index is built at compile time. */
  search: string | null;
}

export type HelpIssueCode =
  | 'ROUTE_WITHOUT_PAGE'
  | 'MENU_WITHOUT_PAGE'
  | 'UNKNOWN_ROUTE'
  | 'MISSING_SHOT'
  | 'MISSING_TRANSLATION'
  | 'MISSING_SECTION'
  | 'UNKNOWN_UI_KEY'
  | 'BAD_FRONT_MATTER'
  | 'DUPLICATE_ID'
  | 'UNKNOWN_LINK'
  | 'SCREEN_WITHOUT_SHOT'
  | 'UNKNOWN_PERMISSION';

export interface HelpIssue {
  code: HelpIssueCode;
  pageId?: string;
  locale?: Locale;
  detail: string;
}

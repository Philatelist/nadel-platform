import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useCarriedHelpState } from './helpOrigin';
import type { Locale } from '@nadel/shared';
import type { HelpNode } from '../../help/types';
import { useT } from '../i18n';

interface HelpContentProps {
  nodes: readonly HelpNode[];
  locale: Locale;
  /** Pages the viewer may open; a link to any other page is shown as plain text. Omitted: all. */
  isPageVisible?: (pageId: string) => boolean;
}

const headingClass: Record<'h2' | 'h3' | 'h4', string> = {
  h2: 'mt-6 scroll-mt-4 font-serif text-2xl font-semibold text-ink',
  h3: 'mt-4 scroll-mt-4 text-lg font-semibold text-ink',
  h4: 'mt-3 scroll-mt-4 font-semibold text-ink',
};

/** A link to another help page; it carries the origin, so the way back survives. */
export function HelpPageLink({
  pageId,
  anchor,
  className = 'text-accent underline',
  children,
}: {
  pageId: string;
  anchor?: string;
  className?: string;
  children: ReactNode;
}) {
  const state = useCarriedHelpState();
  return (
    <Link
      to={{ pathname: `/help/${pageId}`, hash: anchor ? `#${anchor}` : '' }}
      state={state}
      className={className}
    >
      {children}
    </Link>
  );
}

function Callout({ children }: { children: ReactNode }) {
  const t = useT();
  return (
    <aside className="rounded-control border border-warning-line bg-warning-soft px-4 py-3 text-warning-ink">
      <p className="mb-1 font-semibold">{t.help.important}</p>
      <div className="flex flex-col gap-2">{children}</div>
    </aside>
  );
}

type Visible = ((pageId: string) => boolean) | undefined;

function renderNodes(nodes: readonly HelpNode[], locale: Locale, visible: Visible): ReactNode[] {
  return nodes.map((node, index) => renderNode(node, locale, index, visible));
}

function renderNode(node: HelpNode, locale: Locale, key: number, visible: Visible): ReactNode {
  switch (node.type) {
    case 'text':
      return node.text;
    case 'br':
      return <br key={key} />;
    case 'hr':
      return <hr key={key} className="border-line" />;
    case 'img':
      return (
        <img
          key={key}
          src={`/help/img/${locale}/${node.shot}.png`}
          alt={node.alt}
          loading="lazy"
          className="max-w-full rounded-control border border-line"
        />
      );
    case 'ui':
      return (
        <span key={key} className="rounded-control bg-accent-soft px-1.5 py-0.5 font-medium text-ink">
          {node.text}
        </span>
      );
    case 'link':
      if (visible && !visible(node.pageId))
        return <span key={key}>{renderNodes(node.children, locale, visible)}</span>;
      return (
        <HelpPageLink key={key} pageId={node.pageId} anchor={node.anchor}>
          {renderNodes(node.children, locale, visible)}
        </HelpPageLink>
      );
    case 'a':
      return (
        <a key={key} href={node.href} rel="noreferrer noopener" className="text-accent underline">
          {renderNodes(node.children, locale, visible)}
        </a>
      );
    case 'callout':
      return <Callout key={key}>{renderNodes(node.children, locale, visible)}</Callout>;
    case 'h2':
    case 'h3':
    case 'h4': {
      const Heading = node.type;
      return (
        <Heading key={key} id={node.id} className={headingClass[node.type]}>
          {renderNodes(node.children, locale, visible)}
        </Heading>
      );
    }
    case 'ul':
      return (
        <ul key={key} className="list-disc pl-6">
          {renderNodes(node.children, locale, visible)}
        </ul>
      );
    case 'ol':
      return (
        <ol key={key} className="list-decimal pl-6">
          {renderNodes(node.children, locale, visible)}
        </ol>
      );
    case 'li':
      return (
        <li key={key} className="my-1 [&>img]:mt-2">
          {renderNodes(node.children, locale, visible)}
        </li>
      );
    case 'blockquote':
      return (
        <blockquote key={key} className="border-l-4 border-line-strong pl-4 text-ink-muted">
          {renderNodes(node.children, locale, visible)}
        </blockquote>
      );
    case 'table':
      return (
        <div key={key} className="overflow-x-auto">
          <table className="w-full border-collapse text-left text-sm">
            {renderNodes(node.children, locale, visible)}
          </table>
        </div>
      );
    case 'th':
      return (
        <th key={key} className="border border-line bg-accent-soft px-3 py-2 font-semibold">
          {renderNodes(node.children, locale, visible)}
        </th>
      );
    case 'td':
      return (
        <td key={key} className="border border-line px-3 py-2 align-top">
          {renderNodes(node.children, locale, visible)}
        </td>
      );
    case 'pre':
      return (
        <pre key={key} className="overflow-x-auto rounded-control bg-accent-soft p-3 font-mono text-sm">
          {renderNodes(node.children, locale, visible)}
        </pre>
      );
    case 'code':
      return (
        <code key={key} className="font-mono text-[0.9em]">
          {renderNodes(node.children, locale, visible)}
        </code>
      );
    case 'p':
    case 'thead':
    case 'tbody':
    case 'tr':
    case 'strong':
    case 'em': {
      const Tag = node.type;
      return <Tag key={key}>{renderNodes(node.children, locale, visible)}</Tag>;
    }
    default: {
      const unreachable: never = node;
      return unreachable;
    }
  }
}

/** Renders the compiled node tree with React elements; no raw HTML is ever injected. */
export function HelpContent({ nodes, locale, isPageVisible }: HelpContentProps) {
  return (
    <div className="flex flex-col gap-3 text-[15px] leading-relaxed text-ink">
      {renderNodes(nodes, locale, isPageVisible)}
    </div>
  );
}

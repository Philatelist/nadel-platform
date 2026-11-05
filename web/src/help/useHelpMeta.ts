import { useEffect } from 'react';
import type { HelpBundlePage } from '../../help/types';
import { useDocumentTitle } from '../useDocumentTitle';

type MetaPage = Pick<HelpBundlePage, 'title' | 'description'> & Partial<Pick<HelpBundlePage, 'id' | 'shots'>>;

/** Public origin used for canonical and preview-image addresses. */
export const SITE_URL = (import.meta.env.VITE_SITE_URL ?? 'http://localhost:5173').replace(/\/+$/, '');

/** Sets `content` of the head tag found by `selector` (created from `attrs` when missing); returns the restore function. */
function setHeadTag(
  selector: string,
  tag: 'meta' | 'link',
  attrs: Record<string, string>,
  valueAttr: 'content' | 'href',
  value: string,
): () => void {
  let element = document.head.querySelector<HTMLElement>(selector);
  const created = element === null;
  if (!element) {
    element = document.createElement(tag);
    for (const [name, attrValue] of Object.entries(attrs)) element.setAttribute(name, attrValue);
    document.head.append(element);
  }
  const previous = element.getAttribute(valueAttr);
  element.setAttribute(valueAttr, value);
  return () => {
    if (created) element.remove();
    else if (previous !== null) element.setAttribute(valueAttr, previous);
  };
}

/**
 * Tab title and head tags of a help page: description, canonical address, Open Graph preview and `robots: index, follow`
 * (the rest of the application says `noindex`). Everything is restored on leaving.
 */
export function useHelpMeta(page: MetaPage | undefined): void {
  useDocumentTitle(page?.title);
  const title = page?.title;
  const description = page?.description;
  const id = page?.id;
  const shot = page?.shots?.[0]?.name;
  useEffect(() => {
    if (description === undefined || title === undefined) return;
    const url = `${SITE_URL}/help${id ? `/${id}` : ''}`;
    const restores = [
      setHeadTag('meta[name="description"]', 'meta', { name: 'description' }, 'content', description),
      setHeadTag('meta[name="robots"]', 'meta', { name: 'robots' }, 'content', 'index, follow'),
      setHeadTag('link[rel="canonical"]', 'link', { rel: 'canonical' }, 'href', url),
      setHeadTag('meta[property="og:title"]', 'meta', { property: 'og:title' }, 'content', title),
      setHeadTag(
        'meta[property="og:description"]',
        'meta',
        { property: 'og:description' },
        'content',
        description,
      ),
      setHeadTag('meta[property="og:type"]', 'meta', { property: 'og:type' }, 'content', 'article'),
      setHeadTag('meta[property="og:url"]', 'meta', { property: 'og:url' }, 'content', url),
    ];
    if (shot) {
      restores.push(
        setHeadTag(
          'meta[property="og:image"]',
          'meta',
          { property: 'og:image' },
          'content',
          `${SITE_URL}/help/img/uk/${shot}.png`,
        ),
      );
    }
    return () => {
      for (const restore of restores.reverse()) restore();
    };
  }, [title, description, id, shot]);
}

import { useEffect } from 'react';
import type { HelpBundlePage } from '../../help/types';
import { useDocumentTitle } from '../useDocumentTitle';

type MetaPage = Pick<HelpBundlePage, 'title' | 'description'>;

/** Tab title and meta description for a help page; the previous description is restored on leaving. */
export function useHelpMeta(page: MetaPage | undefined): void {
  useDocumentTitle(page?.title);
  const description = page?.description;
  useEffect(() => {
    if (description === undefined) return;
    let element = document.head.querySelector<HTMLMetaElement>('meta[name="description"]');
    const created = element === null;
    if (!element) {
      element = document.createElement('meta');
      element.name = 'description';
      document.head.append(element);
    }
    const previous = element.content;
    element.content = description;
    return () => {
      if (created) element.remove();
      else element.content = previous;
    };
  }, [description]);
}

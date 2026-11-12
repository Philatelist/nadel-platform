import { useEffect } from 'react';
import { useLocation } from 'react-router';

/** A malformed escape such as `%` makes decodeURIComponent throw; the raw text is then the id. */
function decodeHash(raw: string): string {
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

/** Scrolls to the element named by the URL hash; waits for it when the section renders after its data loads. */
export function useScrollToHash(): void {
  const { hash } = useLocation();
  useEffect(() => {
    if (hash.length < 2) return;
    const id = decodeHash(hash.slice(1));
    const scroll = (): boolean => {
      const el = document.getElementById(id);
      if (!el) return false;
      el.scrollIntoView();
      return true;
    };
    if (scroll()) return;
    const observer = new MutationObserver(() => {
      if (scroll()) observer.disconnect();
    });
    observer.observe(document.body, { childList: true, subtree: true });
    const timer = window.setTimeout(() => observer.disconnect(), 5000);
    return () => {
      observer.disconnect();
      window.clearTimeout(timer);
    };
  }, [hash]);
}

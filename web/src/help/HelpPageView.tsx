import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { QueryBoundary } from '../components/QueryBoundary';
import { PageHeader } from '../components/ui';
import { useLocale, useT } from '../i18n';
import { NotFoundPage } from '../pages/NotFoundPage';
import type { HelpBundlePage } from '../../help/types';
import { HelpContent } from './HelpContent';
import { useHelpViewer } from './helpViewer';
import { isPageVisible, visiblePageIds } from './helpVisibility';
import { useHelpBundle } from './useHelpBundle';
import { useHelpMeta } from './useHelpMeta';

function PageBody({ page, visibleIds }: { page: HelpBundlePage; visibleIds: ReadonlySet<string> }) {
  const t = useT();
  const { locale } = useLocale();
  const { hash } = useLocation();
  const [active, setActive] = useState<string | null>(null);
  useHelpMeta(page);

  useEffect(() => {
    if (hash) document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView?.();
  }, [hash, page.id]);

  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.find((entry) => entry.isIntersecting);
        if (visible) setActive(visible.target.id);
      },
      { rootMargin: '0px 0px -70% 0px' },
    );
    for (const heading of page.headings) {
      const element = document.getElementById(heading.id);
      if (element) observer.observe(element);
    }
    return () => observer.disconnect();
  }, [page]);

  return (
    <div className="flex flex-col gap-4" lang={locale}>
      <PageHeader
        title={page.title}
        description={page.description}
        actions={
          <Link to="/help" className="text-accent underline">
            {t.help.toContents}
          </Link>
        }
      />
      <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
        {page.headings.length > 0 && (
          <nav
            aria-label={t.help.onThisPage}
            className="lg:sticky lg:top-4 lg:order-2 lg:w-64 lg:shrink-0 rounded-panel border border-line bg-surface p-4"
          >
            <p className="mb-2 text-sm font-semibold text-ink-muted">{t.help.onThisPage}</p>
            <ul className="flex flex-col gap-1.5 text-sm">
              {page.headings.map((heading) => (
                <li key={heading.id} className={heading.level === 3 ? 'pl-3' : undefined}>
                  <a
                    href={`#${heading.id}`}
                    aria-current={active === heading.id ? 'location' : undefined}
                    className={active === heading.id ? 'font-semibold text-accent' : 'text-ink'}
                  >
                    {heading.text}
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        )}
        <div className="min-w-0 flex-1 lg:order-1">
          <HelpContent nodes={page.body} locale={locale} isPageVisible={(id) => visibleIds.has(id)} />
        </div>
      </div>
    </div>
  );
}

function HiddenPage() {
  const t = useT();
  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t.help.hidden} />
      <p>
        <Link to="/help" className="text-accent underline">
          {t.help.toContents}
        </Link>
      </p>
    </div>
  );
}

export function HelpPageView() {
  const { id } = useParams();
  const query = useHelpBundle();
  const viewer = useHelpViewer();
  return (
    <QueryBoundary query={query}>
      {(bundle) => {
        const page = bundle.pages.find((candidate) => candidate.id === id);
        if (!page) return <NotFoundPage />;
        if (!isPageVisible(page, viewer)) return <HiddenPage />;
        return <PageBody page={page} visibleIds={visiblePageIds(bundle.pages, viewer)} />;
      }}
    </QueryBoundary>
  );
}

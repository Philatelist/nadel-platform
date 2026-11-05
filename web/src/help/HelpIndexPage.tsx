import { useState } from 'react';
import { QueryBoundary } from '../components/QueryBoundary';
import { PageHeader, Panel, SearchField } from '../components/ui';
import { useT } from '../i18n';
import { HELP_SECTIONS, type HelpBundle } from '../../help/types';
import { HelpPageLink } from './HelpContent';
import { HelpBackLink } from './helpOrigin';
import { useHelpViewer } from './helpViewer';
import { isPageVisible } from './helpVisibility';
import { useHelpBundle } from './useHelpBundle';
import { useHelpSearch } from './useHelpSearch';
import { useHelpMeta } from './useHelpMeta';

function HelpResults({ bundle, query }: { bundle: HelpBundle; query: string }) {
  const t = useT();
  const viewer = useHelpViewer();
  const results = useHelpSearch(bundle, query, viewer);
  if (results.length === 0) return <p role="status">{t.help.noResults}</p>;
  return (
    <Panel title={t.help.search}>
      <ul className="flex flex-col gap-1.5">
        {results.map((result) => (
          <li key={result.id}>
            <HelpPageLink pageId={result.id}>{result.title}</HelpPageLink>
          </li>
        ))}
      </ul>
    </Panel>
  );
}

export function HelpIndexPage() {
  const t = useT();
  useHelpMeta({ title: t.help.title, description: t.help.contents });
  const query = useHelpBundle();
  const viewer = useHelpViewer();
  const [search, setSearch] = useState('');
  const searching = search.trim() !== '';
  return (
    <div className="flex flex-col gap-4">
      <HelpBackLink />
      <PageHeader title={t.help.title} />
      <SearchField
        label={t.help.search}
        placeholder={t.help.search}
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      <QueryBoundary query={query}>
        {(bundle) =>
          searching ? (
            <HelpResults bundle={bundle} query={search} />
          ) : (
            <div className="flex flex-col gap-4">
              {HELP_SECTIONS.map((section) => {
                const pages = bundle.pages.filter(
                  (page) => page.section === section && isPageVisible(page, viewer),
                );
                if (pages.length === 0) return null;
                return (
                  <Panel key={section} title={t.help.sections[section]}>
                    <ul className="flex flex-col gap-1.5">
                      {pages.map((page) => (
                        <li key={page.id}>
                          <HelpPageLink pageId={page.id}>{page.title}</HelpPageLink>
                        </li>
                      ))}
                    </ul>
                  </Panel>
                );
              })}
            </div>
          )
        }
      </QueryBoundary>
    </div>
  );
}

import { Link } from 'react-router';
import { QueryBoundary } from '../components/QueryBoundary';
import { PageHeader, Panel } from '../components/ui';
import { useT } from '../i18n';
import { HELP_SECTIONS } from '../../help/types';
import { useHelpBundle } from './useHelpBundle';
import { useHelpMeta } from './useHelpMeta';

export function HelpIndexPage() {
  const t = useT();
  useHelpMeta({ title: t.help.title, description: t.help.contents });
  const query = useHelpBundle();
  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t.help.title} />
      <QueryBoundary query={query}>
        {(bundle) => (
          <div className="flex flex-col gap-4">
            {HELP_SECTIONS.map((section) => {
              const pages = bundle.pages.filter((page) => page.section === section);
              if (pages.length === 0) return null;
              return (
                <Panel key={section} title={t.help.sections[section]}>
                  <ul className="flex flex-col gap-1.5">
                    {pages.map((page) => (
                      <li key={page.id}>
                        <Link to={`/help/${page.id}`} className="text-accent underline">
                          {page.title}
                        </Link>
                      </li>
                    ))}
                  </ul>
                </Panel>
              );
            })}
          </div>
        )}
      </QueryBoundary>
    </div>
  );
}

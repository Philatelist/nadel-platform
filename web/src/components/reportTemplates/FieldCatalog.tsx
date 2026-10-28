import { useGetReportTemplateCatalog, type ReportTemplateField } from '@nadel/api-client';
import { format } from '@nadel/shared';
import { Fragment, useState, type ReactNode } from 'react';
import { QueryBoundary } from '../QueryBoundary';
import { Badge, Button, SearchField } from '../ui';
import { useT } from '../../i18n';
import { fieldDescription } from '../../reportTemplateFields';

type Group = 'SINGLE' | 'PLOTS' | 'MONTHS' | 'METERS' | 'WATER' | 'PAYMENTS';
const groupOrder: readonly Group[] = ['SINGLE', 'PLOTS', 'MONTHS', 'METERS', 'WATER', 'PAYMENTS'];

/** Copies text; true when it worked. The legacy path covers a browser or page without the Clipboard API. */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const area = document.createElement('textarea');
      area.value = text;
      document.body.appendChild(area);
      area.select();
      const done = typeof document.execCommand === 'function' && document.execCommand('copy');
      area.remove();
      return done;
    } catch {
      return false;
    }
  }
}

/** Fills a `{token}` of a catalog text with a node; the node is a literal sample, so it is marked as code. */
function withCode(text: string, token: string, value: string): ReactNode {
  const [before = '', after = ''] = text.split(`{${token}}`);
  return (
    <Fragment>
      {before}
      <code data-code className="font-mono">
        {value}
      </code>
      {after}
    </Fragment>
  );
}

/** The seeded types are named by the catalog; a custom type is only known by its id here. */
function chargeTypeOf(
  field: ReportTemplateField,
  defaults: Readonly<Record<string, string>>,
  custom: string,
) {
  if (field.pattern === undefined) return undefined;
  const code = field.key.slice(field.pattern.indexOf('<'));
  return defaults[code] ?? custom;
}

/** The placeholders a template can use, grouped by row set, searchable, each with a copy button. */
export function FieldCatalog() {
  const t = useT();
  const s = t.reportTemplates;
  const query = useGetReportTemplateCatalog();
  const [search, setSearch] = useState('');
  const [copied, setCopied] = useState<{ key: string; ok: boolean } | null>(null);
  const defaults: Readonly<Record<string, string>> = t.chargeTypes.defaultNames;

  const describe = (field: ReportTemplateField) =>
    fieldDescription(t, field, chargeTypeOf(field, defaults, s.catalog.customChargeType)) ?? '';

  async function copy(key: string) {
    setCopied({ key, ok: await copyText(`\${${key}}`) });
  }

  return (
    <section aria-label={s.catalog.title} className="flex flex-col gap-3">
      <h2 className="text-[17px] font-semibold">{s.catalog.title}</h2>
      <p className="text-ink-muted">{withCode(s.catalog.intro, 'placeholder', '${totals.debt}')}</p>
      <SearchField
        label={s.catalog.search}
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      <QueryBoundary query={query}>
        {(fields) => {
          const needle = search.trim().toLowerCase();
          const shown = fields.filter(
            (field) =>
              needle === '' ||
              field.key.toLowerCase().includes(needle) ||
              describe(field).toLowerCase().includes(needle),
          );
          if (shown.length === 0) return <p className="text-ink-muted">{s.catalog.empty}</p>;
          return (
            <>
              {groupOrder.map((group) => {
                const items = shown.filter((field) => (field.rowSet ?? 'SINGLE') === group);
                if (items.length === 0) return null;
                return (
                  <div key={group} className="flex flex-col gap-2">
                    <h3 className="text-[15px] font-semibold">{s.catalog.groups[group]}</h3>
                    <ul aria-label={s.catalog.groups[group]} className="flex flex-col gap-2">
                      {items.map((field) => (
                        <li
                          key={field.key}
                          className="flex flex-col gap-1 rounded-control border border-line bg-surface p-3"
                        >
                          <div className="flex flex-wrap items-center gap-2">
                            <code className="font-mono text-sm">{`\${${field.key}}`}</code>
                            <Badge tone="neutral">{s.catalog.types[field.type]}</Badge>
                            <Button
                              variant="secondary"
                              aria-label={format(s.catalog.copy, { description: describe(field) })}
                              onClick={() => void copy(field.key)}
                            >
                              {s.catalog.copyButton}
                            </Button>
                            <span role="status" className="text-sm text-ink-muted">
                              {copied?.key === field.key
                                ? copied.ok
                                  ? s.catalog.copied
                                  : s.catalog.copyFailed
                                : ''}
                            </span>
                          </div>
                          <p>{describe(field)}</p>
                          {field.example !== undefined && (
                            <p className="text-sm text-ink-muted">
                              {withCode(s.catalog.example, 'value', field.example)}
                            </p>
                          )}
                        </li>
                      ))}
                    </ul>
                  </div>
                );
              })}
            </>
          );
        }}
      </QueryBoundary>
    </section>
  );
}

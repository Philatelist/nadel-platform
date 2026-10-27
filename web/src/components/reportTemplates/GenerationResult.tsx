import { format } from '@nadel/shared';
import { UserContent } from '../UserContent';
import { Notice } from '../ui';
import { useT } from '../../i18n';
import type { GenerationWarnings } from '../../reportTemplateWarnings';

const shownWarnings = 10;

/** What a generated file reports: a plain success, or the warning count with the first warnings in words. */
export function GenerationResult({ warnings }: { warnings: GenerationWarnings }) {
  const s = useT().reportTemplates;
  if (warnings.count === 0) return <Notice tone="success">{s.generate.done}</Notice>;
  return (
    <Notice tone="warning">
      <p>{format(s.generate.warningsCount, { count: warnings.count })}</p>
      <ul className="mt-2 list-disc space-y-1 pl-5">
        {warnings.details.slice(0, shownWarnings).map((warning, index) => (
          <li key={index}>
            {warning.sheet !== undefined && <UserContent>{warning.sheet}</UserContent>}
            {warning.cell !== undefined && <UserContent>{` ${warning.cell}`}</UserContent>}
            {(warning.sheet !== undefined || warning.cell !== undefined) && ': '}
            {warning.placeholder !== undefined && (
              <>
                <code data-user-content>{`\${${warning.placeholder}}`}</code>
                {' — '}
              </>
            )}
            {s.warnings[warning.code]}
            {warning.reason !== undefined && ` ${s.valueReasons[warning.reason]}.`}
          </li>
        ))}
      </ul>
      <p className="mt-2">{format(s.generate.warningsSheet, { sheet: s.warningsSheet.name })}</p>
    </Notice>
  );
}

import type { RequisiteField } from '@nadel/api-client';
import { Link } from 'react-router';
import { useT } from '../../i18n';
import { Notice } from '../ui';

/**
 * Says which cooperative requisites block invoices: a missing IBAN links to Settings (the chairman fills it in),
 * a missing EDRPOU is set by the platform operator. Renders nothing when `missing` is empty.
 */
export function RequisitesWarning({
  missing,
  showLink = true,
}: {
  missing: readonly RequisiteField[];
  /** False on the requisites form itself, where the link would point to the page the reader is on. */
  showLink?: boolean;
}) {
  const t = useT();
  if (missing.length === 0) return null;
  const r = t.invoices.requisites;
  return (
    <Notice tone="warning">
      <p className="font-medium">{r.title}</p>
      <ul className="list-disc pl-5">
        {missing.map((field) => (
          <li key={field}>
            {r[field]}
            {field === 'IBAN' && showLink && (
              <>
                {' '}
                <Link to="/settings" className="text-accent underline">
                  {r.ibanLink}
                </Link>
              </>
            )}
          </li>
        ))}
      </ul>
    </Notice>
  );
}

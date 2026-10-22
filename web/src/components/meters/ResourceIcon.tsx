import type { MeterResource } from '@nadel/api-client';
import { useT } from '../../i18n';

/** Lightning for electricity, drop for water: different shapes, so the resource never relies on color alone. */
export function ResourceIcon({ resource, className = '' }: { resource: MeterResource; className?: string }) {
  const name = useT().meters.resource[resource];
  return (
    <span
      role="img"
      aria-label={name}
      title={name}
      className={`inline-flex shrink-0 items-center align-middle ${
        resource === 'WATER' ? 'text-accent' : 'text-warning-ink'
      } ${className}`}
    >
      <svg
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="currentColor"
        aria-hidden="true"
        focusable="false"
      >
        {resource === 'WATER' ? (
          <path d="M12 2.5c-3.2 4.2-6 7.4-6 11a6 6 0 0 0 12 0c0-3.6-2.8-6.8-6-11z" />
        ) : (
          <path d="M13.5 2 5 13.5h5.5L9.5 22 19 9.8h-5.7L13.5 2z" />
        )}
      </svg>
    </span>
  );
}

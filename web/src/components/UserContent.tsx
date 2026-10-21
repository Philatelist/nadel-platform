import type { ReactNode } from 'react';

/**
 * Wraps text a person entered (names, plot numbers, notes, reasons, file names). It is the one place that says
 * "this text is not translated": the language checks skip these spans.
 */
export function UserContent({ children }: { children: ReactNode }) {
  return <span data-user-content>{children}</span>;
}

/**
 * Like `format` from `@nadel/shared`, but every value is user content: the catalog text stays plain and each
 * substituted value is wrapped in a user-content marker.
 */
export function formatUser(template: string, vars: Readonly<Record<string, ReactNode>>): ReactNode {
  return template.split(/(\{\w+\})/).map((part, index) => {
    const key = /^\{(\w+)\}$/.exec(part)?.[1];
    const value = key === undefined ? undefined : vars[key];
    if (value === undefined) return part;
    return typeof value === 'string' || typeof value === 'number' ? (
      <UserContent key={index}>{value}</UserContent>
    ) : (
      <span key={index}>{value}</span>
    );
  });
}

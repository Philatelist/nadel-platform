import { Fragment, type ReactNode } from 'react';
import { Notice } from '../ui';

export interface RunWarningItem {
  key: string;
  text: ReactNode;
}

/** Fills `{name}` placeholders of a catalog text with nodes (names, links); the catalog text itself stays plain. */
export function richFormat(template: string, values: Readonly<Record<string, ReactNode>>): ReactNode {
  return template.split(/(\{\w+\})/).map((part, index) => {
    const key = /^\{(\w+)\}$/.exec(part)?.[1];
    const value = key === undefined ? undefined : values[key];
    return <Fragment key={index}>{value === undefined ? part : value}</Fragment>;
  });
}

/** The warnings of a range run preview or booked run, one line each; renders nothing without warnings. */
export function RunWarnings({ items, label }: { items: readonly RunWarningItem[]; label: string }) {
  if (items.length === 0) return null;
  return (
    <Notice tone="warning">
      <ul aria-label={label} className="flex flex-col gap-1">
        {items.map((item) => (
          <li key={item.key}>{item.text}</li>
        ))}
      </ul>
    </Notice>
  );
}

import type { ReactNode } from 'react';
import { Panel } from './ui';

export function FullPageMessage({
  title,
  message,
  children,
}: {
  title: string;
  message: string;
  children?: ReactNode;
}) {
  return (
    <main className="grid h-full place-items-center px-4">
      <div className="w-full max-w-[480px]">
        <Panel>
          <h1 className="font-serif text-3xl font-semibold text-ink">{title}</h1>
          <p className="text-ink-muted">{message}</p>
          {children && <div className="flex flex-wrap gap-2">{children}</div>}
        </Panel>
      </div>
    </main>
  );
}

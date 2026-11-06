import type { ReactNode } from 'react';
import { LocaleSwitch } from './LocaleSwitch';
import { ThemeSwitch } from './ThemeSwitch';

/** The frame of a page for a visitor who is not signed in: brand, language and theme switches, optional action. */
export function PublicShell({
  brand,
  action,
  maxWidth = 'max-w-5xl',
  children,
}: {
  brand: ReactNode;
  action?: ReactNode;
  maxWidth?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex min-h-full flex-col">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line bg-surface px-4 py-2">
        {brand}
        <div className="flex flex-wrap items-center gap-3">
          <LocaleSwitch />
          <ThemeSwitch />
          {action}
        </div>
      </header>
      <main className={`mx-auto w-full ${maxWidth} flex-1 px-4 py-6`}>{children}</main>
    </div>
  );
}

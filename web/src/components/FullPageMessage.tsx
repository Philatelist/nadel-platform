import type { ReactNode } from 'react';

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
      <div className="flex w-full max-w-md flex-col items-start gap-4 rounded-2xl border border-line bg-surface p-8">
        <h1 className="font-display text-2xl font-bold">{title}</h1>
        <p className="text-muted">{message}</p>
        {children}
      </div>
    </main>
  );
}

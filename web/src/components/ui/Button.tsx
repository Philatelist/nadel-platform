import type { ComponentProps } from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'danger';

const base =
  'inline-flex min-h-11 items-center justify-center gap-2 rounded-control border px-4 text-[15px] font-medium no-underline transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50 aria-disabled:cursor-not-allowed aria-disabled:opacity-50';

const variants: Record<ButtonVariant, string> = {
  primary: 'border-accent bg-accent text-on-accent hover:bg-accent-hover hover:border-accent-hover',
  secondary: 'border-line-strong bg-surface text-ink hover:bg-accent-soft',
  danger: 'border-danger bg-surface text-danger hover:bg-danger-soft',
};

/** Class names of a button; use it for links styled as buttons. */
export function buttonClass(variant: ButtonVariant = 'primary'): string {
  return `${base} ${variants[variant]}`;
}

export function Button({
  variant = 'primary',
  type = 'button',
  className = '',
  ...rest
}: ComponentProps<'button'> & { variant?: ButtonVariant }) {
  return <button {...rest} type={type} className={`${buttonClass(variant)} ${className}`.trim()} />;
}

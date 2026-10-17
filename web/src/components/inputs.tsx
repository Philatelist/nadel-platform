import type { ComponentProps } from 'react';
import type { ControlProps } from './ui/Field';

const base = 'w-full rounded-md border bg-surface px-3 py-1.5 text-sm aria-invalid:border-danger border-line';

type Extra = Pick<ControlProps, 'invalid'>;

export function TextInput({ invalid, className = '', ...rest }: ComponentProps<'input'> & Extra) {
  return <input {...rest} aria-invalid={invalid} className={`${base} ${className}`} />;
}

export function TextArea({ invalid, className = '', ...rest }: ComponentProps<'textarea'> & Extra) {
  return <textarea {...rest} aria-invalid={invalid} className={`${base} ${className}`} />;
}

export function SelectInput({ invalid, className = '', ...rest }: ComponentProps<'select'> & Extra) {
  return <select {...rest} aria-invalid={invalid} className={`${base} ${className}`} />;
}

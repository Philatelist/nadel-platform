import type { ComponentProps } from 'react';
import { Field } from './Field';
import { controlClass } from './fieldStyles';

export function Select({
  label,
  error,
  hint,
  className = '',
  ...rest
}: { label: string; error?: string; hint?: string } & Omit<ComponentProps<'select'>, 'id'>) {
  return (
    <Field label={label} error={error} hint={hint}>
      {({ invalid, ...control }) => (
        <select
          {...rest}
          {...control}
          aria-invalid={invalid}
          className={`${controlClass} ${className}`.trim()}
        />
      )}
    </Field>
  );
}

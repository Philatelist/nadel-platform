import type { ComponentProps } from 'react';
import { Field } from './Field';
import { controlClass } from './fieldStyles';

interface FieldProps {
  label: string;
  error?: string;
  hint?: string;
}

export function TextField({
  label,
  error,
  hint,
  className = '',
  ...rest
}: FieldProps & Omit<ComponentProps<'input'>, 'id'>) {
  return (
    <Field label={label} error={error} hint={hint}>
      {({ invalid, ...control }) => (
        <input
          {...rest}
          {...control}
          aria-invalid={invalid}
          className={`${controlClass} ${className}`.trim()}
        />
      )}
    </Field>
  );
}

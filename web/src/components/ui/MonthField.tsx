import type { ComponentProps } from 'react';
import { Field } from './Field';
import { controlClass } from './fieldStyles';

/** A labelled month picker; the value is "YYYY-MM" (an empty string while the user clears it). */
export function MonthField({
  label,
  value,
  onChange,
  error,
  hint,
  ...rest
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  hint?: string;
} & Omit<ComponentProps<'input'>, 'id' | 'type' | 'value' | 'onChange'>) {
  return (
    <Field label={label} error={error} hint={hint}>
      {({ invalid, ...control }) => (
        <input
          {...rest}
          {...control}
          type="month"
          value={value}
          aria-invalid={invalid}
          onChange={(e) => onChange(e.target.value)}
          className={controlClass}
        />
      )}
    </Field>
  );
}

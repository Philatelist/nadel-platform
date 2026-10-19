import { useId } from 'react';

export interface RadioOption<TValue extends string> {
  value: TValue;
  label: string;
  description?: string;
}

/** A fieldset of native radios sharing one name; each option is a 44px-high label target. */
export function RadioGroup<TValue extends string>({
  legend,
  options,
  value,
  onChange,
  error,
  disabled,
}: {
  legend: string;
  options: readonly RadioOption<TValue>[];
  value: TValue;
  onChange: (value: TValue) => void;
  error?: string;
  disabled?: boolean;
}) {
  const name = useId();
  const errorId = `${name}-error`;
  return (
    <fieldset
      disabled={disabled}
      aria-describedby={error ? errorId : undefined}
      className="flex flex-col gap-2 disabled:opacity-60"
    >
      <legend className="mb-1 text-sm font-medium text-ink-muted">{legend}</legend>
      {options.map((option) => (
        <label
          key={option.value}
          className="flex min-h-11 cursor-pointer items-start gap-3 rounded-control border border-line-strong bg-surface px-3 py-2 has-[:checked]:border-accent has-[:checked]:bg-accent-soft"
        >
          <input
            type="radio"
            name={name}
            value={option.value}
            checked={option.value === value}
            onChange={() => onChange(option.value)}
            className="mt-1 size-4 accent-accent"
          />
          <span className="flex flex-col">
            <span className="text-[15px] text-ink">{option.label}</span>
            {option.description && <span className="text-[13px] text-ink-subtle">{option.description}</span>}
          </span>
        </label>
      ))}
      {error && (
        <p id={errorId} role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </fieldset>
  );
}

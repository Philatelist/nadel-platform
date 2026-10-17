import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Field } from './Field';

describe('Field', () => {
  it('labels the control', () => {
    render(
      <Field label="Email">{(c) => <input id={c.id} aria-describedby={c['aria-describedby']} />}</Field>,
    );
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
  });

  it('links the error to the control through aria-describedby', () => {
    render(
      <Field label="Email" error="Required">
        {(c) => <input id={c.id} aria-describedby={c['aria-describedby']} />}
      </Field>,
    );
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Required');
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription('Required');
  });
});

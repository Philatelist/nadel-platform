import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithApp } from '../test/render';
import { LocaleSwitch } from './LocaleSwitch';

describe('LocaleSwitch', () => {
  it('labels each language from its own catalog and names it for assistive technology', () => {
    renderWithApp(<LocaleSwitch />);
    const uk = screen.getByRole('button', { name: 'Українська' });
    const en = screen.getByRole('button', { name: 'English' });
    expect(uk).toHaveTextContent('УКР');
    expect(en).toHaveTextContent('ENG');
    expect(en).toHaveAttribute('aria-pressed', 'true');
    expect(uk).toHaveAttribute('aria-pressed', 'false');
  });
});

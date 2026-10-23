import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { RecalcHint } from './RecalcHint';

let permissions: string[] = [];
vi.mock('../../me', () => ({
  useCan: (permission: string) => permissions.includes(permission),
}));

describe('RecalcHint', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'en'));

  it('points holders of RECALCULATION_RUN to the new recalculation', () => {
    permissions = ['RECALCULATION_RUN'];
    const { container } = renderWithApp(<RecalcHint />);
    expect(container).toHaveTextContent(
      'Past charges have not changed. If needed — Charges → Recalculation.',
    );
    expect(screen.getByRole('link', { name: 'Recalculation' })).toHaveAttribute(
      'href',
      '/charges/recalculations/new',
    );
  });

  it('is absent without the permission', () => {
    permissions = ['CHARGES_VIEW'];
    const { container } = renderWithApp(<RecalcHint />);
    expect(container).toBeEmptyDOMElement();
  });

  it('is in Ukrainian by default', () => {
    window.localStorage.setItem('nadel.locale', 'uk');
    permissions = ['RECALCULATION_RUN'];
    const { container } = renderWithApp(<RecalcHint />);
    expect(container).toHaveTextContent(
      'Минулі нарахування не змінено. За потреби — Нарахування → Перерахунок.',
    );
  });
});

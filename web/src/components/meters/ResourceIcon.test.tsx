import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ResourceIcon } from './ResourceIcon';

describe('ResourceIcon', () => {
  beforeEach(() => window.localStorage.setItem('nadel.locale', 'uk'));

  it('names the resource for assistive technology and in a tooltip, with a different shape per resource', () => {
    const { container } = renderWithApp(
      <>
        <ResourceIcon resource="ELECTRICITY" />
        <ResourceIcon resource="WATER" />
      </>,
    );
    const power = screen.getByRole('img', { name: 'Електроенергія' });
    const drop = screen.getByRole('img', { name: 'Вода' });
    expect(power).toHaveAttribute('title', 'Електроенергія');
    expect(drop).toHaveAttribute('title', 'Вода');
    const [a, b] = Array.from(container.querySelectorAll('path')).map((p) => p.getAttribute('d'));
    expect(a).not.toBe(b);
  });
});

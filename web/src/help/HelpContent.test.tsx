import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { HelpNode } from '../../help/types';
import { renderWithApp } from '../test/render';
import { HelpContent } from './HelpContent';

const shot = (name: string, alt: string): HelpNode[] => [{ type: 'img', shot: name, alt }];

describe('HelpContent screenshots', () => {
  it('a screenshot that failed on one page does not stay failed when the next page shows another one', () => {
    const { rerender } = renderWithApp(<HelpContent nodes={shot('first', 'Перший')} locale="uk" />);
    fireEvent.error(screen.getByRole('img', { name: 'Перший' }));
    expect(screen.getByRole('img', { name: 'Перший' }).tagName).toBe('SPAN');

    rerender(<HelpContent nodes={shot('second', 'Другий')} locale="uk" />);
    const next = screen.getByRole('img', { name: 'Другий' });
    expect(next.tagName).toBe('IMG');
    expect(next).toHaveAttribute('src', '/help/img/uk/second.png');
  });
});

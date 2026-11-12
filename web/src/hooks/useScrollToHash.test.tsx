import { act, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useScrollToHash } from './useScrollToHash';

function Probe({ late }: { late?: boolean }) {
  useScrollToHash();
  return late ? null : <h2 id="my-charges">x</h2>;
}

describe('useScrollToHash', () => {
  afterEach(() => vi.restoreAllMocks());

  it('scrolls to the element named by the hash', () => {
    const spy = vi.fn();
    Element.prototype.scrollIntoView = spy;
    render(
      <MemoryRouter initialEntries={['/account#my-charges']}>
        <Probe />
      </MemoryRouter>,
    );
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('waits for an element that appears later', async () => {
    const spy = vi.fn();
    Element.prototype.scrollIntoView = spy;
    render(
      <MemoryRouter initialEntries={['/account#my-charges']}>
        <Probe late />
      </MemoryRouter>,
    );
    expect(spy).not.toHaveBeenCalled();
    await act(async () => {
      const h = document.createElement('h2');
      h.id = 'my-charges';
      document.body.appendChild(h);
      await Promise.resolve();
    });
    expect(spy).toHaveBeenCalledTimes(1);
  });
});

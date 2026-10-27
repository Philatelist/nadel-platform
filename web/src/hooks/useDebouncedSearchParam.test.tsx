import { act, render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDebouncedSearchParam } from './useDebouncedSearchParam';

function Search({ value }: { value: string }) {
  useDebouncedSearchParam('q', value, 300, { resetPage: true });
  return null;
}

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.pathname + location.search}</output>;
}

function setup(value: string, mounted = true) {
  const view = (show: boolean, v: string) => (
    <MemoryRouter initialEntries={['/plots?page=2']}>
      <Where />
      <Routes>
        <Route path="/plots" element={show ? <Search value={v} /> : null} />
      </Routes>
    </MemoryRouter>
  );
  const result = render(view(mounted, value));
  const where = () => result.getByTestId('where').textContent;
  return { result, where, view };
}

describe('useDebouncedSearchParam', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('writes the trimmed value once after typing stops and resets the page', () => {
    const { result, where, view } = setup('');
    result.rerender(view(true, '9'));
    result.rerender(view(true, ' 9 '));
    act(() => void vi.advanceTimersByTime(299));
    expect(where()).toBe('/plots?page=2');
    act(() => void vi.advanceTimersByTime(1));
    expect(where()).toBe('/plots?q=9');
    act(() => void vi.advanceTimersByTime(1000));
    expect(where()).toBe('/plots?q=9');
  });

  it('does not touch the URL when the page unmounts before the delay', () => {
    const { result, where, view } = setup('');
    result.rerender(view(true, '9'));
    result.rerender(view(false, '9'));
    act(() => void vi.advanceTimersByTime(1000));
    expect(where()).toBe('/plots?page=2');
  });
});

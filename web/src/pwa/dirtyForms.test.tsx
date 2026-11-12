import { act, render, renderHook } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { useAnyDirtyForm, useDirtyGuard } from './dirtyForms';

function Form({ initial }: { initial: string }) {
  const [text, setText] = useState(initial);
  useDirtyGuard(text !== '');
  return <input aria-label="text" value={text} onChange={(event) => setText(event.target.value)} />;
}

describe('useDirtyGuard', () => {
  it('is dirty while a form holds input and clean after it is emptied or unmounted', () => {
    const { result } = renderHook(() => useAnyDirtyForm());
    expect(result.current).toBe(false);
    const view = render(<Form initial="12" />);
    expect(result.current).toBe(true);
    view.rerender(<Form initial="12" />);
    expect(result.current).toBe(true);
    view.unmount();
    expect(result.current).toBe(false);
  });

  it('counts several forms: dirty until the last one is clean', () => {
    const { result } = renderHook(() => useAnyDirtyForm());
    const first = render(<Form initial="a" />);
    const second = render(<Form initial="b" />);
    expect(result.current).toBe(true);
    act(() => first.unmount());
    expect(result.current).toBe(true);
    act(() => second.unmount());
    expect(result.current).toBe(false);
  });
});

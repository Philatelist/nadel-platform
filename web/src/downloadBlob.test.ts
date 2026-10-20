import { afterEach, describe, expect, it, vi } from 'vitest';
import { downloadBlob } from './downloadBlob';

describe('downloadBlob', () => {
  afterEach(() => vi.restoreAllMocks());

  it('clicks a temporary download link, then removes it and revokes the URL', () => {
    const create = vi.fn(() => 'blob:fake');
    const revoke = vi.fn();
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke });
    let attachedOnClick = false;
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
      attachedOnClick = document.querySelector('a[download]') !== null;
    });
    const blob = new Blob(['x']);

    downloadBlob(blob, 'file.xlsx');

    expect(create).toHaveBeenCalledWith(blob);
    expect(click).toHaveBeenCalledOnce();
    expect(attachedOnClick).toBe(true);
    const clicked = click.mock.contexts[0] as HTMLAnchorElement;
    expect(clicked.download).toBe('file.xlsx');
    expect(clicked.getAttribute('href')).toBe('blob:fake');
    expect(document.body.contains(clicked)).toBe(false);
    expect(revoke).toHaveBeenCalledWith('blob:fake');
  });
});

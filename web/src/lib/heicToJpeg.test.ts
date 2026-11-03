import { afterEach, describe, expect, it, vi } from 'vitest';
import { heicToJpeg, isHeicFile } from './heicToJpeg';

const heic = () => new File(['x'], 'IMG_1.HEIC', { type: 'image/heic' });

describe('heicToJpeg', () => {
  afterEach(() => vi.restoreAllMocks());

  it('recognises HEIC by type or extension', () => {
    expect(isHeicFile(heic())).toBe(true);
    expect(isHeicFile(new File(['x'], 'a.HEIF', { type: '' }))).toBe(true);
    expect(isHeicFile(new File(['x'], 'a.jpg', { type: 'image/jpeg' }))).toBe(false);
  });

  it('draws the decoded image on a canvas and returns a .jpg', async () => {
    const close = vi.fn();
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue({ width: 2, height: 2, close }));
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as never);
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((cb) =>
      cb(new Blob(['jpeg'], { type: 'image/jpeg' })),
    );
    const result = await heicToJpeg(heic());
    expect(result?.name).toBe('IMG_1.jpg');
    expect(result?.type).toBe('image/jpeg');
    expect(close).toHaveBeenCalled();
  });

  it('returns undefined when the browser cannot decode the file', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockRejectedValue(new Error('unsupported')));
    expect(await heicToJpeg(heic())).toBeUndefined();
  });
});

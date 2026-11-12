import { afterEach, describe, expect, it, vi } from 'vitest';
import { isHeicFile, normalizeImage } from './normalizeImage';

const heic = () => new File(['x'], 'IMG_1.HEIC', { type: 'image/heic' });
const jpeg = () => new File(['x'], 'IMG_2.jpeg', { type: 'image/jpeg' });

function stubDecoder(width: number, height: number) {
  const close = vi.fn();
  const createImageBitmap = vi.fn().mockResolvedValue({ width, height, close });
  vi.stubGlobal('createImageBitmap', createImageBitmap);
  const drawImage = vi.fn();
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage } as never);
  const toBlob = vi
    .spyOn(HTMLCanvasElement.prototype, 'toBlob')
    .mockImplementation((cb) => cb(new Blob(['jpeg'], { type: 'image/jpeg' })));
  return { close, createImageBitmap, drawImage, toBlob };
}

describe('normalizeImage', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('recognises HEIC by type or extension', () => {
    expect(isHeicFile(heic())).toBe(true);
    expect(isHeicFile(new File(['x'], 'a.HEIF', { type: '' }))).toBe(true);
    expect(isHeicFile(new File(['x'], 'a.jpg', { type: 'image/jpeg' }))).toBe(false);
  });

  it('always converts HEIC to a .jpg, decoding with the EXIF orientation', async () => {
    const { close, createImageBitmap } = stubDecoder(800, 600);
    const result = await normalizeImage(heic());
    expect(result?.name).toBe('IMG_1.jpg');
    expect(result?.type).toBe('image/jpeg');
    expect(createImageBitmap).toHaveBeenCalledWith(expect.any(File), { imageOrientation: 'from-image' });
    expect(close).toHaveBeenCalled();
  });

  it('returns undefined when the browser cannot decode a HEIC file', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockRejectedValue(new Error('unsupported')));
    expect(await normalizeImage(heic())).toBeUndefined();
  });

  it('keeps a small JPEG unchanged', async () => {
    const { toBlob } = stubDecoder(2000, 1500);
    const file = jpeg();
    expect(await normalizeImage(file)).toBe(file);
    expect(toBlob).not.toHaveBeenCalled();
  });

  it('scales a large photo to 2000 px on the long side as JPEG 0.85', async () => {
    const { toBlob, drawImage } = stubDecoder(4000, 3000);
    const result = await normalizeImage(new File(['x'], 'big.png', { type: 'image/png' }));
    expect(result?.name).toBe('big.jpg');
    expect(result?.type).toBe('image/jpeg');
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 2000, 1500);
    expect(toBlob).toHaveBeenCalledWith(expect.any(Function), 'image/jpeg', 0.85);
  });

  it('refuses more than 50 megapixels', async () => {
    const { close } = stubDecoder(8000, 6500);
    expect(await normalizeImage(jpeg())).toBeUndefined();
    expect(close).toHaveBeenCalled();
  });

  it('leaves a JPEG the browser cannot decode to the server', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn().mockRejectedValue(new Error('unsupported')));
    const file = jpeg();
    expect(await normalizeImage(file)).toBe(file);
  });
});

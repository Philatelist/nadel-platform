import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, configureApi, nadelFetch } from './http';

const fetchMock = vi.fn();
const onUnauthorized = vi.fn();

function sentHeaders(): Headers {
  return (fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Headers;
}

describe('nadelFetch', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    onUnauthorized.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    configureApi({ getToken: () => 'tok', onUnauthorized });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('asks for JSON and sends the token by default', async () => {
    fetchMock.mockResolvedValue(
      new Response('{"a":1}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    expect(await nadelFetch('/x')).toEqual({ a: 1 });
    expect(sentHeaders().get('Accept')).toBe('application/json');
    expect(sentHeaders().get('Authorization')).toBe('Bearer tok');
  });

  it('keeps an Accept header set by the caller and never sets Content-Type', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await nadelFetch('/x', { method: 'POST', body: new FormData(), headers: { Accept: 'application/pdf' } });
    expect(sentHeaders().get('Accept')).toBe('application/pdf');
    expect(sentHeaders().has('Content-Type')).toBe(false);
  });

  it('returns a Blob for a successful non-JSON response', async () => {
    const type = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    fetchMock.mockResolvedValue(
      new Response('xlsx-bytes', { status: 200, headers: { 'Content-Type': type } }),
    );
    const result = await nadelFetch<Blob>('/imports/template');
    expect(result).toBeInstanceOf(Blob);
    expect(result.type).toBe(type);
    expect(await result.text()).toBe('xlsx-bytes');
  });

  it('returns undefined for 204 and for an empty body', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    expect(await nadelFetch('/x')).toBeUndefined();
    fetchMock.mockResolvedValueOnce(new Response('', { status: 200 }));
    expect(await nadelFetch('/x')).toBeUndefined();
  });

  it('throws ApiError with the parsed JSON error body', async () => {
    fetchMock.mockResolvedValue(
      new Response('{"code":"X"}', { status: 400, headers: { 'Content-Type': 'application/problem+json' } }),
    );
    const error = await nadelFetch('/x').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 400, body: { code: 'X' } });
  });

  it('throws ApiError without a body when the error body is not JSON', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502 }));
    const error = await nadelFetch('/x').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 502, body: undefined });
  });

  it('calls onUnauthorized on 401', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 401 }));
    await expect(nadelFetch('/x')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });
});

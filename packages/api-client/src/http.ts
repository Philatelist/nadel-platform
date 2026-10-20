/**
 * Single HTTP entry point for the generated client.
 * The app (web or mobile) sets the base address and how to get the token through configureApi.
 */
type TokenProvider = () => string | null | undefined | Promise<string | null | undefined>;

let apiOrigin = '';
let tokenProvider: TokenProvider = () => null;
let onUnauthorized: () => void = () => {};

export function configureApi(options: {
  /** For example "https://api.nadel.app". Empty means same origin (web through the Vite proxy). */
  origin?: string;
  getToken: TokenProvider;
  onUnauthorized?: () => void;
}): void {
  apiOrigin = options.origin ?? '';
  tokenProvider = options.getToken;
  onUnauthorized = options.onUnauthorized ?? (() => {});
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
  ) {
    super(`API ${status}`);
  }
}

export async function nadelFetch<T>(url: string, init?: RequestInit): Promise<T> {
  const token = await tokenProvider();
  const headers = new Headers(init?.headers);
  // Never set Content-Type here: for FormData the browser adds multipart/form-data with the boundary.
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(`${apiOrigin}${url}`, { ...init, headers });

  if (response.status === 401) onUnauthorized();
  if (!response.ok) throw new ApiError(response.status, parseJson(await response.text()));

  const contentType = response.headers.get('Content-Type') ?? '';
  // A successful non-JSON body (a file download) is returned as a Blob; 204 and empty bodies stay undefined.
  if (response.status !== 204 && contentType !== '' && !isJson(contentType)) {
    const blob = await response.blob();
    return (blob.size > 0 ? blob : undefined) as T;
  }
  return parseJson(await response.text()) as T;
}

function isJson(contentType: string): boolean {
  return /[/+]json\b/i.test(contentType);
}

/** Empty or non-JSON text gives undefined; error bodies must never make the error itself unreadable. */
function parseJson(text: string): unknown {
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

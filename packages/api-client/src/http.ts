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
  headers.set('Accept', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(`${apiOrigin}${url}`, { ...init, headers });
  const text = await response.text();
  const body: unknown = text ? JSON.parse(text) : undefined;

  if (response.status === 401) onUnauthorized();
  if (!response.ok) throw new ApiError(response.status, body);

  return body as T;
}

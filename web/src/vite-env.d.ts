/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  /** Public origin of the site (canonical and preview-image addresses of the help); default http://localhost:5173. */
  readonly VITE_SITE_URL?: string;
}

/** Build identifier injected by Vite (`NADEL_BUILD_ID`, default `dev`). */
declare const __BUILD_ID__: string;

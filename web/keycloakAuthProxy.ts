import type { ProxyOptions } from 'vite';

// PWA e2e only (NADEL_E2E_KEYCLOAK_UNDER_AUTH=1): serves the local Keycloak (:8180) on the app origin under `/auth/`,
// like the stand does behind Caddy, so the e2e can prove sign-in and sign-out with the service worker in control.
// Keycloak's hostname is fixed to http://localhost:8180, so its pages and discovery document name that origin; the
// proxy rewrites it to http://localhost:5173/auth in text answers and redirects (the token `iss` and the discovery
// `issuer` stay at :8180 on purpose: the backend validates the former, the client does not compare the two), and moves
// the cookie paths under /auth.
const KEYCLOAK = 'http://localhost:8180';
const PUBLIC = 'http://localhost:5173/auth';

export function keycloakAuthProxy(): Record<string, ProxyOptions> {
  return {
    '/auth': {
      target: KEYCLOAK,
      changeOrigin: true,
      selfHandleResponse: true,
      rewrite: (path) => path.replace(/^\/auth/, ''),
      configure: (proxy) => {
        proxy.on('proxyReq', (req) => req.setHeader('accept-encoding', 'identity'));
        proxy.on('proxyRes', (res, _req, out) => {
          const headers = { ...res.headers };
          if (typeof headers.location === 'string')
            headers.location = headers.location.split(KEYCLOAK).join(PUBLIC);
          const cookies = res.headers['set-cookie'];
          if (cookies) headers['set-cookie'] = cookies.map((c) => c.replace(/Path=\//i, 'Path=/auth/'));
          const type = String(headers['content-type'] ?? '');
          const isText = /json|html|javascript|css|text|xml/.test(type);
          const chunks: Buffer[] = [];
          res.on('data', (chunk: Buffer) => chunks.push(chunk));
          res.on('end', () => {
            let body = Buffer.concat(chunks);
            if (isText) {
              let text = body.toString('utf8');
              const issuer = /"issuer":"[^"]*"/.exec(text)?.[0];
              text = text.split(KEYCLOAK).join(PUBLIC);
              if (issuer) text = text.replace(/"issuer":"[^"]*"/, issuer);
              body = Buffer.from(text, 'utf8');
            }
            delete headers['content-length'];
            headers['content-length'] = String(body.length);
            out.writeHead(res.statusCode ?? 502, headers);
            out.end(body);
          });
        });
      },
    },
  };
}

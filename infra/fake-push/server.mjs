// Fake Web Push service for local development and end-to-end tests. Dependency-free.
// Accepts RFC 8030 pushes at POST /push/<id> and keeps them raw (never decrypts), plus a control API under /control/*.
// Never logs bodies or authorization headers: only method, path kind and status.
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';

const PORT = Number(process.env.PORT ?? 8092);
const MAX_BODY = 8 * 1024;
const KEPT_HEADERS = ['authorization', 'content-encoding', 'content-length', 'ttl', 'urgency', 'topic'];

/** @type {Array<{id: string, receivedAt: string, headers: Record<string, string>, bodyBase64: string}>} */
let received = [];
const gone = new Set();
/** id -> status */
const failing = new Map();

function send(res, status, body, extra = {}) {
  const data = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json',
    'content-length': Buffer.byteLength(data),
    ...extra,
  });
  res.end(data);
}

/** Reads at most MAX_BODY bytes; resolves null when the body is larger. */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) tooLarge = true;
      else chunks.push(c);
    });
    req.on('end', () => resolve(tooLarge ? null : Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function handle(req, res) {
  const url = new URL(req.url ?? '/', 'http://localhost');
  const parts = url.pathname.split('/').filter(Boolean);
  const method = req.method ?? 'GET';
  const [root, kind, a, b] = parts;

  if (root === 'push' && parts.length === 2 && method === 'POST') {
    const id = decodeURIComponent(kind);
    const body = await readBody(req);
    if (body === null) return send(res, 413, { error: 'payload too large' });
    if (gone.has(id)) return send(res, 410, { error: 'gone' });
    if (failing.has(id)) return send(res, failing.get(id), { error: 'forced failure' });
    const headers = {};
    for (const name of KEPT_HEADERS)
      if (req.headers[name] !== undefined) headers[name] = String(req.headers[name]);
    received.push({ id, receivedAt: new Date().toISOString(), headers, bodyBase64: body.toString('base64') });
    return send(res, 201, undefined, {
      location: `/push/${encodeURIComponent(id)}/messages/${randomUUID()}`,
    });
  }

  if (root === 'control') {
    if (kind === 'health' && parts.length === 2 && method === 'GET') return send(res, 200, { ok: true });
    if (kind === 'received' && parts.length === 2 && method === 'GET') {
      const id = url.searchParams.get('id');
      return send(res, 200, id ? received.filter((r) => r.id === id) : received);
    }
    if (kind === 'reset' && parts.length === 2 && method === 'POST') {
      received = [];
      gone.clear();
      failing.clear();
      return send(res, 200, { ok: true });
    }
    if (kind === 'gone' && parts.length === 3 && method === 'POST') {
      gone.add(decodeURIComponent(a));
      return send(res, 200, { ok: true });
    }
    if (kind === 'gone' && parts.length === 3 && method === 'DELETE') {
      gone.delete(decodeURIComponent(a));
      return send(res, 200, { ok: true });
    }
    if (kind === 'fail' && parts.length === 4 && method === 'POST') {
      const status = Number(b);
      if (!Number.isInteger(status) || status < 400 || status > 599)
        return send(res, 400, { error: 'bad status' });
      failing.set(decodeURIComponent(a), status);
      return send(res, 200, { ok: true });
    }
    if (kind === 'fail' && parts.length === 3 && method === 'DELETE') {
      failing.delete(decodeURIComponent(a));
      return send(res, 200, { ok: true });
    }
  }
  return send(res, 404, { error: 'not found' });
}

createServer((req, res) => {
  handle(req, res).catch(() => send(res, 500, { error: 'internal error' }));
}).listen(PORT, () => console.log(`fake-push listening on ${PORT}`));

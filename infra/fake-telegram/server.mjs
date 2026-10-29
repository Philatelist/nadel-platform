// Fake Telegram Bot API for local development and end-to-end tests. Dependency-free.
// Bot API subset under /bot<token>/<method>, plus a control API under /control/*.
// Never logs message texts or tokens: only method names and counts.
import { createServer } from 'node:http';

const PORT = Number(process.env.PORT ?? 8091);
const BOT_USERNAME = process.env.BOT_USERNAME ?? 'nadel_demo_bot';
const MAX_POLL_SECONDS = 25;

/** token -> { updates: [], nextUpdateId, sent: [], nextMessageId, waiters: [] } */
const bots = new Map();
const blocked = new Set();

function bot(token) {
  let state = bots.get(token);
  if (!state) {
    state = { updates: [], nextUpdateId: 1, sent: [], nextMessageId: 1, waiters: [] };
    bots.set(token, state);
  }
  return state;
}

function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) });
  res.end(data);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function parseMultipart(buffer, contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType);
  const boundary = Buffer.from(`--${match[1] ?? match[2]}`);
  const fields = {};
  const files = {};
  let pos = buffer.indexOf(boundary);
  while (pos !== -1) {
    const start = pos + boundary.length;
    if (buffer.subarray(start, start + 2).toString() === '--') break;
    const next = buffer.indexOf(boundary, start);
    if (next === -1) break;
    const part = buffer.subarray(start + 2, next - 2); // strip leading CRLF and trailing CRLF
    const headerEnd = part.indexOf('\r\n\r\n');
    if (headerEnd !== -1) {
      const headers = part.subarray(0, headerEnd).toString('utf8');
      const content = part.subarray(headerEnd + 4);
      const name = /name="([^"]*)"/i.exec(headers)?.[1];
      const filename = /filename="([^"]*)"/i.exec(headers)?.[1];
      if (name) {
        if (filename !== undefined) files[name] = { filename, size: content.length };
        else fields[name] = content.toString('utf8');
      }
    }
    pos = next;
  }
  return { fields, files };
}

async function readParams(req, url) {
  const params = Object.fromEntries(url.searchParams);
  const files = {};
  if (req.method === 'POST') {
    const raw = await readBody(req);
    const type = req.headers['content-type'] ?? '';
    if (raw.length > 0) {
      if (type.includes('application/json')) {
        Object.assign(params, JSON.parse(raw.toString('utf8')));
      } else if (type.includes('application/x-www-form-urlencoded')) {
        Object.assign(params, Object.fromEntries(new URLSearchParams(raw.toString('utf8'))));
      } else if (type.includes('multipart/form-data')) {
        const parsed = parseMultipart(raw, type);
        Object.assign(params, parsed.fields);
        Object.assign(files, parsed.files);
      }
    }
  }
  return { params, files };
}

function parseMarkup(value) {
  if (value === undefined || value === null) return null;
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function wake(state) {
  const waiters = state.waiters.splice(0);
  for (const w of waiters) w();
}

function pendingUpdates(state, offset, limit) {
  return state.updates.filter((u) => u.update_id >= offset).slice(0, limit);
}

function getUpdates(state, params, res) {
  const offset = Number(params.offset ?? 0);
  const limit = Math.min(Math.max(Number(params.limit ?? 100), 1), 100);
  const timeout = Math.min(Math.max(Number(params.timeout ?? 0), 0), MAX_POLL_SECONDS);
  if (offset > 0) state.updates = state.updates.filter((u) => u.update_id >= offset);
  const ready = pendingUpdates(state, offset, limit);
  if (ready.length > 0 || timeout === 0) {
    return json(res, 200, { ok: true, result: ready });
  }
  let done = false;
  const finish = () => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    json(res, 200, { ok: true, result: pendingUpdates(state, offset, limit) });
  };
  const timer = setTimeout(finish, timeout * 1000);
  state.waiters.push(finish);
  res.on('close', () => {
    done = true;
    clearTimeout(timer);
  });
}

function record(state, method, params, files) {
  const chatId = String(params.chat_id ?? '');
  if (blocked.has(chatId)) {
    return {
      status: 403,
      body: { ok: false, error_code: 403, description: 'Forbidden: bot was blocked by the user' },
    };
  }
  const file = files.document;
  const entry = {
    method,
    chatId,
    text: method === 'sendDocument' ? (params.caption ?? null) : (params.text ?? null),
    documentName: file ? file.filename : null,
    documentSize: file ? file.size : null,
    replyMarkup: parseMarkup(params.reply_markup),
    at: new Date().toISOString(),
  };
  state.sent.push(entry);
  const numericChat = Number.isNaN(Number(chatId)) ? chatId : Number(chatId);
  return {
    status: 200,
    body: {
      ok: true,
      result: {
        message_id: state.nextMessageId++,
        chat: { id: numericChat },
        date: Math.floor(Date.now() / 1000),
      },
    },
  };
}

async function handleBot(req, res, url, token, method) {
  if (token.startsWith('bad')) {
    return json(res, 401, { ok: false, error_code: 401, description: 'Unauthorized' });
  }
  const { params, files } = await readParams(req, url);
  const state = bot(token);
  switch (method) {
    case 'getMe':
      return json(res, 200, {
        ok: true,
        result: { id: 1000001, is_bot: true, first_name: 'Demo bot', username: BOT_USERNAME },
      });
    case 'getUpdates':
      return getUpdates(state, params, res);
    case 'sendMessage':
    case 'sendDocument': {
      const { status, body } = record(state, method, params, files);
      return json(res, status, body);
    }
    case 'setMyCommands':
    case 'answerCallbackQuery':
    case 'deleteWebhook':
      return json(res, 200, { ok: true, result: true });
    default:
      return json(res, 404, { ok: false, error_code: 404, description: 'Not Found' });
  }
}

function addUpdate(state, update) {
  if (update.update_id === undefined || update.update_id === null) {
    update.update_id = state.nextUpdateId;
  }
  state.nextUpdateId = Math.max(state.nextUpdateId, Number(update.update_id) + 1);
  state.updates.push(update);
}

function messageUpdate(chatId, text, firstName) {
  return {
    message: {
      message_id: Math.floor(Math.random() * 1_000_000),
      from: { id: Number(chatId), is_bot: false, first_name: firstName ?? 'Demo user' },
      chat: { id: Number(chatId), type: 'private', first_name: firstName ?? 'Demo user' },
      date: Math.floor(Date.now() / 1000),
      text,
    },
  };
}

async function handleControl(req, res, url) {
  const path = url.pathname;
  if (path === '/control/health') {
    res.writeHead(200, { 'content-type': 'text/plain' });
    return res.end('ok');
  }
  if (path === '/control/updates' && req.method === 'POST') {
    const { params } = await readParams(req, url);
    if (!params.token) return json(res, 400, { error: 'token is required' });
    const state = bot(String(params.token));
    let list;
    if (Array.isArray(params.updates)) list = params.updates;
    else if (params.update) list = [params.update];
    else if (params.chatId !== undefined && params.text !== undefined) {
      list = [messageUpdate(params.chatId, params.text, params.firstName)];
    } else return json(res, 400, { error: 'update, updates or chatId+text is required' });
    for (const u of list) addUpdate(state, { ...u });
    wake(state);
    console.log(`control: queued ${list.length} update(s)`);
    return json(res, 200, { queued: list.length });
  }
  if (path === '/control/sent' && req.method === 'GET') {
    const token = url.searchParams.get('token');
    const chatId = url.searchParams.get('chatId');
    const states = token ? [bot(token)] : [...bots.values()];
    const sent = states.flatMap((s) => s.sent).filter((e) => !chatId || e.chatId === chatId);
    return json(res, 200, sent);
  }
  if (path === '/control/reset' && req.method === 'POST') {
    for (const state of bots.values()) wake(state);
    bots.clear();
    blocked.clear();
    console.log('control: reset');
    return json(res, 200, { ok: true });
  }
  const block = /^\/control\/block\/([^/]+)$/.exec(path);
  if (block && (req.method === 'POST' || req.method === 'DELETE')) {
    const chatId = decodeURIComponent(block[1]);
    if (req.method === 'POST') blocked.add(chatId);
    else blocked.delete(chatId);
    return json(res, 200, { ok: true });
  }
  return json(res, 404, { error: 'not found' });
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? '/', 'http://localhost');
    const bm = /^\/bot([^/]+)\/([A-Za-z]+)$/.exec(url.pathname);
    if (bm) {
      console.log(`bot api: ${bm[2]}`);
      return await handleBot(req, res, url, decodeURIComponent(bm[1]), bm[2]);
    }
    if (url.pathname.startsWith('/control/')) return await handleControl(req, res, url);
    return json(res, 404, { ok: false, error_code: 404, description: 'Not Found' });
  } catch (e) {
    console.error(`request failed: ${e.name}`);
    if (!res.headersSent) json(res, 400, { ok: false, error_code: 400, description: 'Bad Request' });
  }
});

server.listen(PORT, () => console.log(`fake telegram listening on ${PORT}`));

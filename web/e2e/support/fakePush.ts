// Control API of the fake Web Push service of the local stack (infra/fake-push, port 8092) and the receiving side of a
// push: the fake keeps every message raw (never decrypts), so a spec makes a synthetic subscription with keys of its
// own (`newSubscriptionKeys`) and decrypts what the backend sent with them (`decrypt`, RFC 8291 aes128gcm).
import { createECDH, createDecipheriv, hkdfSync, randomBytes } from 'node:crypto';

const BASE = process.env.E2E_FAKE_PUSH_URL ?? 'http://localhost:8092';

/** One push the fake service accepted: the headers it keeps and the raw body. */
export interface FakeReceived {
  id: string;
  receivedAt: string;
  headers: Record<string, string>;
  bodyBase64: string;
}

/** The keys of a synthetic subscription: public key (65 bytes), private key (32 bytes), auth secret (16 bytes), base64url. */
export interface SubscriptionKeys {
  p256dh: string;
  privateKey: string;
  auth: string;
}

async function control(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${BASE}${path}`, init);
  if (!response.ok) throw new Error(`fake push ${path}: ${response.status} ${await response.text()}`);
  return response;
}

/** Forgets every message and every `gone` / `fail` mark of the service. */
export async function reset(): Promise<void> {
  await control('/control/reset', { method: 'POST' });
}

/** The messages the service accepted, oldest first; only those of the subscription `id` when given. */
export async function received(id?: string): Promise<FakeReceived[]> {
  const query = id === undefined ? '' : `?id=${encodeURIComponent(id)}`;
  return (await (await control(`/control/received${query}`)).json()) as FakeReceived[];
}

/** From now on the service answers 410 Gone to the subscription `id`. */
export async function gone(id: string): Promise<void> {
  await control(`/control/gone/${encodeURIComponent(id)}`, { method: 'POST' });
}

export async function notGone(id: string): Promise<void> {
  await control(`/control/gone/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/** A P-256 key pair and a 16-byte auth secret, as a browser's `PushSubscription` has them. */
export function newSubscriptionKeys(): SubscriptionKeys {
  const ecdh = createECDH('prime256v1');
  ecdh.generateKeys();
  return {
    p256dh: ecdh.getPublicKey().toString('base64url'),
    privateKey: ecdh.getPrivateKey().toString('base64url'),
    auth: randomBytes(16).toString('base64url'),
  };
}

const text = (value: string) => Buffer.from(value, 'utf8');

/**
 * Decrypts a push body (RFC 8188 aes128gcm content coding, key derivation of RFC 8291 §3.3-3.4). The body is
 * `salt(16) | record size(4) | key id length(1) | key id (the sender's public key) | ciphertext`; the decrypted record
 * ends with the delimiter 0x02 and zero padding, which are removed. Only a single-record message is supported.
 */
export function decrypt(body: Buffer, uaPrivateKey: string, uaPublicKey: string, authSecret: string): Buffer {
  const salt = body.subarray(0, 16);
  const recordSize = body.readUInt32BE(16);
  const idLength = body.readUInt8(20);
  const asPublic = body.subarray(21, 21 + idLength);
  const ciphertext = body.subarray(21 + idLength);
  if (recordSize < ciphertext.length) throw new Error('more than one record: not supported');

  const ecdh = createECDH('prime256v1');
  ecdh.setPrivateKey(Buffer.from(uaPrivateKey, 'base64url'));
  const shared = ecdh.computeSecret(asPublic);

  const keyInfo = Buffer.concat([text('WebPush: info\0'), Buffer.from(uaPublicKey, 'base64url'), asPublic]);
  // PRK_key = HMAC(auth, ecdh) and IKM = HKDF-Expand(PRK_key, key_info || 0x01, 32) are HKDF with salt = auth secret.
  const ikm = Buffer.from(hkdfSync('sha256', shared, Buffer.from(authSecret, 'base64url'), keyInfo, 32));
  const cek = Buffer.from(hkdfSync('sha256', ikm, salt, text('Content-Encoding: aes128gcm\0'), 16));
  const nonce = Buffer.from(hkdfSync('sha256', ikm, salt, text('Content-Encoding: nonce\0'), 12));

  const decipher = createDecipheriv('aes-128-gcm', cek, nonce, { authTagLength: 16 });
  decipher.setAuthTag(ciphertext.subarray(ciphertext.length - 16));
  const record = Buffer.concat([
    decipher.update(ciphertext.subarray(0, ciphertext.length - 16)),
    decipher.final(),
  ]);
  let end = record.length;
  while (end > 0 && record[end - 1] === 0) end -= 1;
  if (end === 0 || record[end - 1] !== 0x02) throw new Error('no 0x02 delimiter: not the last record');
  return record.subarray(0, end - 1);
}

/** The JSON of a received push, decrypted with the synthetic subscription's keys. */
export function decryptJson(message: FakeReceived, keys: SubscriptionKeys): Record<string, unknown> {
  const plain = decrypt(Buffer.from(message.bodyBase64, 'base64'), keys.privateKey, keys.p256dh, keys.auth);
  return JSON.parse(plain.toString('utf8')) as Record<string, unknown>;
}

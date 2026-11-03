// The mail catcher of the local stack (Mailpit, web page and API on port 8025): the letters of the demo cooperative's
// mailbox. Specs search by recipient, so they never depend on the letters of other specs or of the demo.
import { expect } from '@playwright/test';

const BASE = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025/api/v1';

export interface Letter {
  id: string;
  subject: string;
  to: string[];
  attachments: number;
}

interface MailpitMessage {
  ID: string;
  Subject: string;
  To: { Address: string }[];
  Attachments: number;
}

/** The letters addressed to the address, newest first. */
export async function lettersTo(address: string): Promise<Letter[]> {
  const response = await fetch(`${BASE}/search?query=${encodeURIComponent(`to:${address}`)}`);
  if (!response.ok) throw new Error(`Mailpit search: ${response.status}`);
  const body = (await response.json()) as { messages?: MailpitMessage[] };
  return (body.messages ?? []).map((m) => ({
    id: m.ID,
    subject: m.Subject,
    to: m.To.map((t) => t.Address),
    attachments: m.Attachments,
  }));
}

/** Waits for a letter to the address and returns it. */
export async function waitForLetterTo(address: string): Promise<Letter> {
  let found: Letter | undefined;
  await expect
    .poll(
      async () => {
        found = (await lettersTo(address))[0];
        return found !== undefined;
      },
      { message: `a letter to ${address} should arrive`, timeout: 45_000, intervals: [500, 1000, 2000] },
    )
    .toBe(true);
  return found as Letter;
}

/** Removes every letter of the catcher (only when a spec needs an empty box; searching by recipient is enough). */
export async function clear(): Promise<void> {
  const response = await fetch(`${BASE}/messages`, { method: 'DELETE' });
  if (!response.ok) throw new Error(`Mailpit clear: ${response.status}`);
}

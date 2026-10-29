// Control API of the fake Telegram server of the local stack (infra/fake-telegram, port 8091): inject what a member
// writes to the bot, read what the bot sent, block and unblock a chat. The bot of the demo cooperative polls this
// server (the dev profile points nadel.telegram.api-url at it) with the demo bot token.
import { expect, type Page } from '@playwright/test';
import { api, expectCreated } from './api';

const BASE = process.env.E2E_FAKE_TELEGRAM_URL ?? 'http://localhost:8091';

/** The token of the demo cooperative's bot (set by DemoNotifications). */
export const DEMO_BOT_TOKEN = '123456:DEMO-fake-telegram-token';

/** One message the fake server recorded: a text message or a document with its caption. */
export interface FakeSent {
  method: 'sendMessage' | 'sendDocument';
  chatId: string;
  /** The message text, or the caption of a document. */
  text: string | null;
  documentName: string | null;
  documentSize: number | null;
  replyMarkup: unknown;
  at: string;
}

async function control(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${BASE}${path}`, init);
  if (!response.ok) throw new Error(`fake Telegram ${path}: ${response.status} ${await response.text()}`);
  return response;
}

/**
 * A chat id no other run uses. The demo links use 700000001 and 700000002; ids are kept in a range of their own and
 * differ per call, so a spec never needs to reset the server (a reset would also restart the update numbering under
 * the bot's saved offset).
 */
let counter = 0;
export function uniqueChatId(): number {
  counter += 1;
  return 8_000_000_000 + (Date.now() % 100_000_000) * 10 + (counter % 10);
}

/** What the member types in the chat with the bot. */
export async function sendUpdate(chatId: number, text: string, token = DEMO_BOT_TOKEN): Promise<void> {
  await control('/control/updates', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ token, chatId, text }),
  });
}

/** Everything the bot sent to the chat, oldest first. */
export async function sentTo(chatId: number, token = DEMO_BOT_TOKEN): Promise<FakeSent[]> {
  const response = await control(`/control/sent?chatId=${chatId}&token=${encodeURIComponent(token)}`);
  return (await response.json()) as FakeSent[];
}

/** The chat blocks the bot: the next message to it is refused with 403. */
export async function block(chatId: number): Promise<void> {
  await control(`/control/block/${chatId}`, { method: 'POST' });
}

export async function unblock(chatId: number): Promise<void> {
  await control(`/control/block/${chatId}`, { method: 'DELETE' });
}

/**
 * Forgets every bot, update and block of the server. Do not use it while the application runs: the application keeps
 * the id of the last update it took, and a server that restarts its numbering would look like "nothing new" to it.
 */
export async function reset(): Promise<void> {
  await control('/control/reset', { method: 'POST' });
}

/** Waits until the bot has sent the chat a message that satisfies the test; returns it. */
export async function waitForSent(
  chatId: number,
  test: (sent: FakeSent) => boolean,
  description: string,
): Promise<FakeSent> {
  let found: FakeSent | undefined;
  await expect
    .poll(
      async () => {
        found = (await sentTo(chatId)).find(test);
        return found !== undefined;
      },
      {
        message: `the bot should send the chat ${description}`,
        timeout: 45_000,
        intervals: [500, 1000, 2000],
      },
    )
    .toBe(true);
  return found as FakeSent;
}

/**
 * Links the signed-in member's Telegram to the chat the way a member does: «Connect Telegram» issues a one-time code,
 * the member opens the bot with it (here: writes `/start <code>` to the fake server). The page is the member's page.
 */
export async function linkTelegram(member: Page, chatId: number): Promise<void> {
  const offer = expectCreated<{ deepLink: string }>(await api(member, 'POST', '/me/telegram-link'), 200);
  await sendUpdate(chatId, `/start ${codeOf(offer.deepLink)}`);
  await expect
    .poll(
      async () =>
        expectCreated<{ telegramLinked: boolean }>(await api(member, 'GET', '/me/notifications'), 200)
          .telegramLinked,
      { message: 'the bot should link the chat', timeout: 45_000, intervals: [500, 1000, 2000] },
    )
    .toBe(true);
}

/** The one-time code of a deep link `https://t.me/<bot>?start=<code>`. */
export function codeOf(deepLink: string): string {
  const code = new URL(deepLink).searchParams.get('start');
  if (!code) throw new Error(`no start code in the deep link: ${deepLink}`);
  return code;
}

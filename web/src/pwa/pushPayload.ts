import { pushTexts, type PushLocale } from '@nadel/shared';

/** What the service worker shows for a push message; pure so it can be tested outside the worker. */
export interface PushView {
  title: string;
  body: string;
  tag: string;
  url: string;
  icon: string;
  badge: string;
}

const ICON = '/icon-192.png';

function asLocale(lang: unknown): PushLocale {
  return lang === 'en' ? 'en' : 'uk';
}

/** `2026-11` to the month name in the language; any other value is returned as it is. */
export function monthName(value: unknown, locale: PushLocale): string {
  if (typeof value !== 'string') return '';
  const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(value);
  if (!match) return value;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, 1));
  return new Intl.DateTimeFormat(locale === 'en' ? 'en-GB' : 'uk-UA', {
    month: 'long',
    timeZone: 'UTC',
  }).format(date);
}

/** A target is accepted only as a path of this app: it starts with one `/`, never `//` or a scheme. */
export function safeUrl(url: unknown): string {
  if (typeof url !== 'string') return '/';
  if (!url.startsWith('/') || url.startsWith('//') || url.includes('\\')) return '/';
  return url;
}

function parse(raw: string | undefined): Record<string, unknown> {
  if (!raw) return {};
  try {
    const value: unknown = JSON.parse(raw);
    return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

/** Builds the notification from the raw push data (`{v, code, params, url, lang}`); an unknown code gives a generic one. */
export function buildPushView(raw: string | undefined): PushView {
  const data = parse(raw);
  const locale = asLocale(data.lang);
  const texts = pushTexts[locale];
  const code = typeof data.code === 'string' ? data.code : '';
  const params =
    typeof data.params === 'object' && data.params !== null ? (data.params as Record<string, unknown>) : {};
  let body = texts.generic;
  switch (code) {
    case 'INVOICES_ISSUED':
      body = texts.INVOICES_ISSUED.replace('{month}', monthName(params.month ?? params.period, locale));
      break;
    case 'PAYMENT_RECORDED':
    case 'READING_APPROVED':
    case 'READING_REJECTED':
    case 'ANNOUNCEMENT':
    case 'READINGS_TO_APPROVE':
      body = texts[code];
      break;
    default:
      break;
  }
  return {
    title: texts.title,
    body,
    tag: code || 'generic',
    url: safeUrl(data.url),
    icon: ICON,
    badge: ICON,
  };
}

/**
 * Texts of push notifications. The service worker shows them without the page, so they live apart from the big
 * catalogs and are small enough to be bundled into the worker. The server sends a code and parameters, never text.
 */
export type PushLocale = 'uk' | 'en';

export interface PushTexts {
  title: string;
  /** Used for an unknown code. */
  generic: string;
  /** `{month}` is the month name in the notification language. */
  INVOICES_ISSUED: string;
  PAYMENT_RECORDED: string;
  READING_APPROVED: string;
  READING_REJECTED: string;
  ANNOUNCEMENT: string;
  READINGS_TO_APPROVE: string;
}

export const pushTexts: Readonly<Record<PushLocale, PushTexts>> = {
  uk: {
    title: 'Наділ',
    generic: 'Наділ',
    INVOICES_ISSUED: 'Новий рахунок за {month}',
    PAYMENT_RECORDED: 'Платіж зараховано',
    READING_APPROVED: 'Показ лічильника прийнято',
    READING_REJECTED: 'Показ лічильника відхилено',
    ANNOUNCEMENT: 'Нове оголошення від правління',
    READINGS_TO_APPROVE: 'Нові покази на перевірку',
  },
  en: {
    title: 'Nadel',
    generic: 'Nadel',
    INVOICES_ISSUED: 'New invoice for {month}',
    PAYMENT_RECORDED: 'Payment recorded',
    READING_APPROVED: 'Meter reading accepted',
    READING_REJECTED: 'Meter reading rejected',
    ANNOUNCEMENT: 'New announcement from the board',
    READINGS_TO_APPROVE: 'New readings to review',
  },
};

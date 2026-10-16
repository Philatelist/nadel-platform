import { messages, pickLocale, type Messages } from '@nadel/shared';
import { createContext, useContext, type ReactNode } from 'react';

const locale = pickLocale(typeof navigator === 'undefined' ? [] : navigator.languages);
const I18nContext = createContext<Messages>(messages(locale));

export function I18nProvider({ children }: { children: ReactNode }) {
  return <I18nContext value={messages(locale)}>{children}</I18nContext>;
}

export function useT(): Messages {
  return useContext(I18nContext);
}

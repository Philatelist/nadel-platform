import { createContext, useContext } from 'react';

/** The element the Layout reserves beside `main`; the help panel is rendered into it. */
export const HelpPanelSlotContext = createContext<HTMLElement | null>(null);

export function useHelpPanelSlot(): HTMLElement | null {
  return useContext(HelpPanelSlotContext);
}

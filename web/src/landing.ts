import type { Me } from '@nadel/api-client';
import { menuItems } from './nav';

/**
 * The home screen of a signed-in person: the first item of their menu (the account for an owner without the plots
 * list, the plots list for plot people, the readings round or the register for a meter reader). A person whose menu
 * is empty (no plot, no permission) lands on the account, which says they have no plots yet.
 */
export function landingPath(me: Me): string {
  return menuItems(me)[0]?.to ?? '/account';
}

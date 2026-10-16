/** Source catalog. Every other locale must provide the same keys (type `Messages`). */
export const en = {
  app: { name: 'Nadel' },
  auth: {
    signIn: 'Sign in',
    signOut: 'Sign out',
    signingIn: 'Signing in…',
    signInHint: 'Sign in to see your plots and charges.',
  },
  nav: { cabinet: 'Account', map: 'Map' },
  me: { title: 'My account', roles: 'Roles', cooperative: 'Cooperative', noCooperative: 'Platform operator' },
  roles: {
    CHAIRMAN: 'Chairman',
    TREASURER: 'Treasurer',
    ELECTRICIAN: 'Electrician',
    MEMBER: 'Member',
    PLATFORM_OPERATOR: 'Platform operator',
  },
  map: {
    title: 'Cooperative map',
    plot: 'Plot',
    area: 'Area',
    sotky: 'sotkas',
    debt: 'Debt',
    commonLand: 'Common land',
    empty: 'No plots yet. An administrator can upload the cooperative map.',
  },
  status: {
    RED: 'Debt above threshold',
    YELLOW: 'Has debt',
    GREEN: 'No debt',
    GREY: 'Common land',
  },
  errors: {
    generic: 'Could not load data. Please try again.',
    retry: 'Retry',
  },
} as const;

type Widen<T> = { -readonly [K in keyof T]: T[K] extends string ? string : Widen<T[K]> };
export type Messages = Widen<typeof en>;

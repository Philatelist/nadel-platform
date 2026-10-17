/** Source catalog. Every other locale must provide the same keys (type `Messages`). */
export const en = {
  app: { name: 'Nadel' },
  auth: {
    signIn: 'Sign in',
    signOut: 'Sign out',
    signingIn: 'Signing in…',
    signInHint: 'Sign in to see your plots and charges.',
  },
  nav: {
    account: 'My account',
    members: 'Members',
    roles: 'Roles',
    audit: 'Audit log',
    language: 'Language',
  },
  state: { loading: 'Loading…', empty: 'Nothing here yet.', error: 'Could not load data. Please try again.' },
  access: {
    denied: "You don't have access to this page",
    notFound: 'Not found',
    notLinkedTitle: 'Account not linked',
    noCooperativeTitle: 'No cooperative',
    noCooperative:
      'You are the platform operator and do not belong to any cooperative, so there is no cooperative data to show.',
  },
  account: {
    title: 'My account',
    cooperative: 'Cooperative',
    roles: 'Roles',
    permissions: 'What you can do',
    noPermissions: 'Your roles give no extra permissions. You can see your own data.',
    details: 'My details',
  },
  member: {
    fields: {
      name: 'Name',
      phones: 'Phones',
      email: 'Email',
      postalAddress: 'Postal address',
      deliveryChannel: 'Preferred channel',
      telegramUsername: 'Telegram username',
      viberPhone: 'Viber phone',
      notes: 'Notes',
      roles: 'Roles',
      none: '—',
    },
  },
  channel: { EMAIL: 'Email', TELEGRAM: 'Telegram', VIBER: 'Viber', PAPER: 'Paper' },
  permission: {
    MEMBERS_VIEW: {
      name: 'View members',
      description: 'See all members and their contact details and notes',
    },
    MEMBERS_EDIT: { name: 'Edit members', description: 'Add, edit, archive and restore members' },
    ROLES_MANAGE: {
      name: 'Manage roles',
      description:
        'Create, edit and delete custom roles, change permissions of base roles, assign roles to members',
    },
    AUDIT_VIEW: { name: 'View audit log', description: 'Read the audit log' },
    PLOTS_VIEW: { name: 'View plots', description: 'See all plots with details' },
    PLOTS_EDIT: { name: 'Edit plots', description: 'Add, edit and archive plots' },
    OWNERSHIP_EDIT: { name: 'Edit ownership', description: 'Add, change and correct plot ownership' },
    IMPORT_RUN: { name: 'Run import', description: 'Run the Excel import' },
  },
  roles: {
    CHAIRMAN: 'Chairman',
    TREASURER: 'Treasurer',
    MEMBER: 'Member',
    PLATFORM_OPERATOR: 'Platform operator',
  },
  members: {
    title: 'Members',
    searchPlaceholder: 'Search by name, phone or email',
    showArchived: 'Show archived',
    archived: 'Archived',
    empty: 'No members yet',
    nothingFound: 'Nothing found',
    back: 'Back to members',
  },
  pagination: { label: 'Pages', previous: 'Previous', next: 'Next', pageOf: 'Page {page} of {total}' },
  problem: {
    VALIDATION_FAILED: 'Some fields are not valid. Please check them and try again.',
    MALFORMED_REQUEST: 'The request could not be understood.',
    UNAUTHENTICATED: 'Your session has expired. Please sign in again.',
    MEMBER_NOT_LINKED:
      'Your account is not linked to a member of this cooperative. Please contact your chairman.',
    PERMISSION_DENIED: "You don't have permission to do this.",
    NOT_FOUND: 'Not found',
    EMAIL_TAKEN: 'Another active member already uses this email.',
    ROLE_NAME_TAKEN: 'A role with this name already exists.',
    ROLE_IN_USE: 'This role is assigned to members and cannot be deleted.',
    LAST_CHAIRMAN: 'The cooperative must keep at least one chairman.',
    BASE_ROLE_IMMUTABLE: 'Base roles cannot be renamed or deleted.',
    UNKNOWN: 'Something went wrong. Please try again.',
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
    retry: 'Try again',
  },
} as const;

type Widen<T> = { -readonly [K in keyof T]: T[K] extends string ? string : Widen<T[K]> };
export type Messages = Widen<typeof en>;

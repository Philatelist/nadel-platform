import type { Member, MemberAccess as MemberAccessData, Permission } from '@nadel/api-client';
import { ApiError } from '@nadel/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { MemberAccess } from './MemberAccess';

let permissions: Permission[] = ['MEMBERS_VIEW', 'MEMBERS_INVITE'];
type Opts = { mutation: { onError: (e: unknown) => void } };
const invite = vi.fn();
const resend = vi.fn();
const disable = vi.fn();
let inviteOptions: Opts;
let resendOptions: Opts;
let disableOptions: Opts;

vi.mock('../../me', () => ({
  useCan: (p: Permission) => permissions.includes(p),
}));
vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useInviteMember: (options: Opts) => {
    inviteOptions = options;
    return { mutate: invite, isPending: false };
  },
  useResendMemberInvitation: (options: Opts) => {
    resendOptions = options;
    return { mutate: resend, isPending: false };
  },
  useDisableMemberAccess: (options: Opts) => {
    disableOptions = options;
    return { mutate: disable, isPending: false };
  },
}));

const member = {
  id: 'm1',
  lastName: 'Shevchenko',
  firstName: 'Taras',
  phones: [],
  roles: [],
} as unknown as Member;

function show(access: MemberAccessData): void {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <MemberAccess member={member} access={access} />
    </QueryClientProvider>,
  );
}
const button = (name: string) => screen.queryByRole('button', { name });

describe('MemberAccess', () => {
  beforeEach(() => {
    permissions = ['MEMBERS_VIEW', 'MEMBERS_INVITE'];
    vi.clearAllMocks();
  });

  it('NOT_INVITED: shows the status and an invite button that sends the invitation', () => {
    show({ status: 'NOT_INVITED' });
    expect(screen.getByText('Not invited')).toBeInTheDocument();
    expect(button('Send again')).toBeNull();
    expect(button('Turn off access')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Invite to the system' }));
    expect(invite).toHaveBeenCalledWith({ id: 'm1' });
  });

  it('INVITED: send again and turn off, no invite', () => {
    show({ status: 'INVITED', invitedAt: '2026-10-21T10:00:00Z' });
    expect(screen.getByText(/Invited 21 Oct 2026, \d\d:\d\d, waiting for a password/)).toBeInTheDocument();
    expect(button('Invite to the system')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Send again' }));
    expect(resend).toHaveBeenCalledWith({ id: 'm1' });
    expect(button('Turn off access')).toBeEnabled();
  });

  it('ACTIVE: shows the last sign-in or that there was none, only turn off', () => {
    show({ status: 'ACTIVE', lastSignInAt: '2026-10-22T10:00:00Z' });
    expect(screen.getByText(/Has access, last sign-in 22 Oct 2026/)).toBeInTheDocument();
    expect(button('Invite to the system')).toBeNull();
    expect(button('Send again')).toBeNull();
    expect(button('Turn off access')).toBeInTheDocument();
  });

  it('ACTIVE without a sign-in yet', () => {
    show({ status: 'ACTIVE' });
    expect(screen.getByText('Has access, has not signed in yet')).toBeInTheDocument();
  });

  it('DISABLED: invite again, no turn off', () => {
    show({ status: 'DISABLED', disabledAt: '2026-10-23T10:00:00Z' });
    expect(screen.getByText('Access turned off')).toBeInTheDocument();
    expect(button('Invite to the system')).toBeEnabled();
    expect(button('Turn off access')).toBeNull();
  });

  it('without MEMBERS_INVITE: only the status', () => {
    permissions = ['MEMBERS_VIEW'];
    show({ status: 'INVITED', invitedAt: '2026-10-21T10:00:00Z' });
    expect(screen.getByText(/Invited/)).toBeInTheDocument();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('a blocked invite is disabled and says why', () => {
    show({ status: 'NOT_INVITED', inviteBlockedBy: 'EMAIL_MISSING' });
    expect(button('Invite to the system')).toBeDisabled();
    expect(screen.getByText('Add an e-mail — it will be the login')).toBeInTheDocument();
  });

  it('a missing mailbox is explained', () => {
    show({ status: 'NOT_INVITED', inviteBlockedBy: 'MAILBOX_NOT_CONFIGURED' });
    expect(button('Invite to the system')).toBeDisabled();
    expect(screen.getByText('Set up the cooperative mailbox')).toBeInTheDocument();
  });

  it('turning access off asks first and names the member', () => {
    show({ status: 'ACTIVE' });
    fireEvent.click(screen.getByRole('button', { name: 'Turn off access' }));
    expect(
      screen.getByText('Turn off access for Shevchenko Taras? The card and its data stay.'),
    ).toBeInTheDocument();
    expect(disable).not.toHaveBeenCalled();
    fireEvent.click(screen.getAllByRole('button', { name: 'Turn off access' }).at(-1)!);
    expect(disable).toHaveBeenCalledWith({ id: 'm1' });
  });

  it('shows the refusal for an e-mail used in another cooperative', () => {
    show({ status: 'NOT_INVITED' });
    act(() => inviteOptions.mutation.onError(new ApiError(409, { code: 'EMAIL_IN_OTHER_COOPERATIVE' })));
    expect(screen.getByText('This e-mail is already used in another cooperative')).toBeInTheDocument();
  });

  it('shows why a letter was not sent', () => {
    show({ status: 'NOT_INVITED' });
    act(() =>
      inviteOptions.mutation.onError(
        new ApiError(502, { code: 'INVITATION_NOT_SENT', failureCode: 'SMTP_AUTH_FAILED' }),
      ),
    );
    expect(screen.getByText(/The invitation was not sent: .+\. Try again\./)).toBeInTheDocument();
  });

  it('shows the refusal when resending to a member who was not invited', () => {
    show({ status: 'INVITED' });
    act(() => resendOptions.mutation.onError(new ApiError(409, { code: 'NOT_INVITED' })));
    expect(screen.getByText('This member has not been invited yet.')).toBeInTheDocument();
  });

  it('shows the refusal when turning off access that was never granted', () => {
    show({ status: 'ACTIVE' });
    act(() => disableOptions.mutation.onError(new ApiError(409, { code: 'ACCESS_NOT_GRANTED' })));
    expect(screen.getByText('This member has no access.')).toBeInTheDocument();
  });
});

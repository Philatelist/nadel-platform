import { screen } from '@testing-library/react';
import type { Member } from '@nadel/api-client';
import { describe, expect, it } from 'vitest';
import { renderWithApp } from '../test/render';
import { MemberDetails } from './MemberDetails';

const member: Member = {
  id: 'm1',
  lastName: 'Іваненко',
  firstName: 'Іван',
  phones: [],
  deliveryChannel: 'TELEGRAM',
  roles: [],
  telegramLinked: true,
} as unknown as Member;

describe('MemberDetails', () => {
  it('shows whether Telegram is connected', () => {
    renderWithApp(<MemberDetails member={member} />);
    expect(screen.getByText('connected')).toBeInTheDocument();
    expect(screen.getByText('Telegram', { selector: 'dt' })).toBeInTheDocument();
  });

  it('shows not connected', () => {
    renderWithApp(<MemberDetails member={{ ...member, telegramLinked: false }} />);
    expect(screen.getByText('not connected')).toBeInTheDocument();
  });

  it('lists the roles unless hideRoles is set', () => {
    const { unmount } = renderWithApp(<MemberDetails member={member} />);
    expect(screen.getByText('Roles', { selector: 'dt' })).toBeInTheDocument();
    unmount();
    renderWithApp(<MemberDetails member={member} hideRoles />);
    expect(screen.queryByText('Roles', { selector: 'dt' })).toBeNull();
  });
});

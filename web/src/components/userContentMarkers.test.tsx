import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithApp } from '../test/render';
import { ChargeTypeName } from './ChargeTypeName';
import { RoleDescription, RoleName, RoleNameList } from './RoleName';
import { formatUser, UserContent } from './UserContent';

describe('user-content markers', () => {
  it('marks a custom role and a customised base role, not a default base role', () => {
    renderWithApp(
      <RoleNameList
        roles={[
          { name: 'Chairman', baseCode: 'CHAIRMAN', nameCustomized: false },
          { name: 'Head', baseCode: 'TREASURER', nameCustomized: true },
          { name: 'Electrician', nameCustomized: true },
        ]}
      />,
    );
    expect(screen.getByText('Chairman')).not.toHaveAttribute('data-user-content');
    expect(screen.getByText('Head')).toHaveAttribute('data-user-content');
    expect(screen.getByText('Electrician')).toHaveAttribute('data-user-content');
  });

  it('shows a default role from the catalog, unmarked', () => {
    renderWithApp(<RoleName role={{ name: 'x', baseCode: 'MEMBER', nameCustomized: false }} />);
    expect(screen.getByText('Member')).not.toHaveAttribute('data-user-content');
  });

  it('marks a customised role description only', () => {
    const { container } = renderWithApp(
      <RoleDescription
        role={{ description: 'Reads meters', baseCode: undefined, descriptionCustomized: true }}
      />,
    );
    expect(container.querySelector('[data-user-content]')).toHaveTextContent('Reads meters');
  });

  it('marks a renamed charge type, not a default-named one', () => {
    renderWithApp(
      <>
        <ChargeTypeName type={{ code: 'WATER', name: 'Water', nameCustomized: false }} />
        <ChargeTypeName type={{ code: 'LAND_TAX', name: 'Land levy', nameCustomized: true }} />
        <ChargeTypeName type={{ name: 'Roads', nameCustomized: true }} />
      </>,
    );
    expect(screen.getByText('Water')).toBeInTheDocument();
    expect(screen.queryByText('Water')?.hasAttribute('data-user-content')).toBe(false);
    expect(screen.getByText('Land levy')).toHaveAttribute('data-user-content');
    expect(screen.getByText('Roads')).toHaveAttribute('data-user-content');
  });

  it('formatUser wraps only the substituted text values', () => {
    const { container } = render(
      <p>{formatUser('Plot {number} of {who}', { number: '12а', who: <i>them</i> })}</p>,
    );
    expect(container.querySelectorAll('[data-user-content]')).toHaveLength(1);
    expect(container.querySelector('[data-user-content]')).toHaveTextContent('12а');
    expect(container).toHaveTextContent('Plot 12а of them');
  });

  it('UserContent renders the marker on a span', () => {
    render(<UserContent>Ivanenko</UserContent>);
    expect(screen.getByText('Ivanenko').tagName).toBe('SPAN');
  });
});

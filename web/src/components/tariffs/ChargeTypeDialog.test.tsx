import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ChargeType } from '@nadel/api-client';
import { describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../../test/render';
import { ChargeTypeDialog } from './ChargeTypeDialog';

vi.mock('../../me', () => ({ useOptionalMe: () => null }));

const seeded: ChargeType = {
  id: 'c1',
  code: 'ELECTRICITY',
  name: 'Electricity',
  nameCustomized: false,
  description: 'Per kWh',
  active: false,
  position: 1,
};

function renderDialog(props: Partial<Parameters<typeof ChargeTypeDialog>[0]> = {}) {
  const onSubmit = vi.fn();
  const onCancel = vi.fn();
  renderWithApp(<ChargeTypeDialog busy={false} onSubmit={onSubmit} onCancel={onCancel} {...props} />);
  return { onSubmit, onCancel };
}

describe('ChargeTypeDialog', () => {
  it('adds a type with a trimmed name and no active checkbox', async () => {
    const user = userEvent.setup();
    const { onSubmit } = renderDialog();
    expect(screen.getByRole('heading', { name: 'New charge type' })).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).toBeNull();
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    await user.type(screen.getByLabelText('Name'), '  Road repair ');
    await user.type(screen.getByLabelText('Description'), 'One-off');
    await user.click(save);
    expect(onSubmit).toHaveBeenCalledWith({ name: 'Road repair', description: 'One-off', active: true });
  });

  it('shows the default name of a seeded type and sends it back unchanged when only the flag changes', async () => {
    const user = userEvent.setup();
    const { onSubmit } = renderDialog({ type: seeded });
    expect(screen.getByLabelText('Name')).toHaveValue('Electricity');
    expect(screen.getByLabelText('Description')).toHaveValue('Per kWh');
    const active = screen.getByRole('checkbox', { name: /Active/ });
    expect(active).not.toBeChecked();
    await user.click(active);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(onSubmit).toHaveBeenCalledWith({ name: 'Electricity', description: 'Per kWh', active: true });
  });

  it('shows a server error, a busy state and cancels', async () => {
    const user = userEvent.setup();
    const { onCancel } = renderDialog({ type: seeded, busy: true, error: 'Name taken' });
    expect(screen.getByRole('alert')).toHaveTextContent('Name taken');
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalled();
  });
});

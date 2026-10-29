import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, type AnnouncementSendResult } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithApp } from '../test/render';
import { AnnouncementFormPage } from './AnnouncementFormPage';

const mutate = vi.fn();
const navigate = vi.fn();
let streets: string[];

vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => navigate,
}));

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useListPlotStreets: () => ({ data: streets }),
  useSendAnnouncement: () => ({ mutate, isPending: false }),
}));

const previewResult: AnnouncementSendResult = {
  counts: { telegram: 3, email: 2, paper: 1 },
  paperMembers: [{ memberId: 'm1', name: 'Петренко Петро' }],
};

/** Calls the success callback of the mutation with the result for the given `preview` flag. */
function answer(result: AnnouncementSendResult) {
  mutate.mockImplementation((_vars: unknown, options: { onSuccess: (r: AnnouncementSendResult) => void }) =>
    options.onSuccess(result),
  );
}

async function typeText(text: string) {
  const user = userEvent.setup();
  await user.click(screen.getByLabelText('Текст оголошення'));
  await user.paste(text);
  return user;
}

describe('AnnouncementFormPage', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    vi.clearAllMocks();
    streets = ['Вишнева', 'Садова'];
    answer(previewResult);
  });

  it('counts characters and blocks above 2000', async () => {
    renderWithApp(<AnnouncementFormPage />);
    expect(screen.getByText('0 / 2000')).toBeInTheDocument();
    await typeText('Привіт');
    expect(screen.getByText('6 / 2000')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.clear(screen.getByLabelText('Текст оголошення'));
    await user.click(screen.getByLabelText('Текст оголошення'));
    await user.paste('я'.repeat(2001));
    expect(screen.getByText('2001 / 2000')).toBeInTheDocument();
    expect(screen.getByText('Не більше 2000 символів')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Попередній перегляд' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Надіслати' })).toBeDisabled();
  });

  it('counts code points, not UTF-16 units', async () => {
    renderWithApp(<AnnouncementFormPage />);
    await typeText('😀😀');
    expect(screen.getByText('2 / 2000')).toBeInTheDocument();
  });

  it('keeps send disabled for a blank text', async () => {
    renderWithApp(<AnnouncementFormPage />);
    await typeText('   ');
    expect(screen.getByRole('button', { name: 'Попередній перегляд' })).toBeDisabled();
  });

  it('requires a preview before send and any edit invalidates it', async () => {
    renderWithApp(<AnnouncementFormPage />);
    const user = await typeText('Збори в суботу');
    const send = screen.getByRole('button', { name: 'Надіслати' });
    expect(send).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Попередній перегляд' }));
    expect(mutate).toHaveBeenCalledWith(
      {
        data: { text: 'Збори в суботу', streets: [], channels: ['TELEGRAM', 'EMAIL'] },
        params: { preview: true },
      },
      expect.anything(),
    );
    expect(screen.getByText('Telegram: 3, електронна пошта: 2, на папері: 1')).toBeInTheDocument();
    expect(screen.getByText('Петренко Петро')).toBeInTheDocument();
    expect(send).toBeEnabled();
    await user.click(screen.getByLabelText('Електронна пошта'));
    expect(send).toBeDisabled();
    expect(screen.queryByText('Петренко Петро')).not.toBeInTheDocument();
  });

  it('invalidates the preview when the text is edited', async () => {
    renderWithApp(<AnnouncementFormPage />);
    const user = await typeText('Раз');
    await user.click(screen.getByRole('button', { name: 'Попередній перегляд' }));
    expect(screen.getByRole('button', { name: 'Надіслати' })).toBeEnabled();
    await user.type(screen.getByLabelText('Текст оголошення'), '!');
    expect(screen.getByRole('button', { name: 'Надіслати' })).toBeDisabled();
  });

  it('needs at least one channel', async () => {
    renderWithApp(<AnnouncementFormPage />);
    const user = await typeText('Текст');
    await user.click(screen.getByLabelText('Telegram'));
    await user.click(screen.getByLabelText('Електронна пошта'));
    expect(screen.getByText('Оберіть хоча б один канал')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Попередній перегляд' })).toBeDisabled();
  });

  it('sends the chosen streets and goes to the announcement', async () => {
    renderWithApp(<AnnouncementFormPage />);
    const user = await typeText('Вода вимкнена');
    expect(screen.queryByLabelText('Садова')).not.toBeInTheDocument();
    await user.click(screen.getByLabelText('Вулиці'));
    expect(screen.getByRole('button', { name: 'Попередній перегляд' })).toBeDisabled();
    await user.click(screen.getByLabelText('Садова'));
    await user.click(screen.getByLabelText('Telegram'));
    await user.click(screen.getByRole('button', { name: 'Попередній перегляд' }));
    answer({ ...previewResult, announcementId: 'a1' });
    await user.click(screen.getByRole('button', { name: 'Надіслати' }));
    expect(mutate).toHaveBeenLastCalledWith(
      {
        data: { text: 'Вода вимкнена', streets: ['Садова'], channels: ['EMAIL'] },
        params: { preview: false },
      },
      expect.anything(),
    );
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/announcements/a1'));
  });

  it('shows the message for ANNOUNCEMENT_TOO_LONG', async () => {
    mutate.mockImplementation((_v: unknown, options: { onError: (e: unknown) => void }) =>
      options.onError(new ApiError(400, { code: 'ANNOUNCEMENT_TOO_LONG', status: 400, title: 'x' })),
    );
    renderWithApp(<AnnouncementFormPage />);
    const user = await typeText('Текст');
    await user.click(screen.getByRole('button', { name: 'Попередній перегляд' }));
    expect(await screen.findByText('Оголошення довше за 2000 символів.')).toBeInTheDocument();
  });
});

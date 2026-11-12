import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError, type MeterReading } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { SubmitReadingDialog } from './SubmitReadingDialog';

const submit = vi.fn();
let mutationOptions: { mutation: { onError: (error: unknown) => void } };
const normalizeImage = vi.fn();
const check = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  checkPlotReading: (...args: unknown[]) => check(...args),
  useSubmitPlotReadingWithPhoto: (o: typeof mutationOptions) => {
    mutationOptions = o;
    return { mutate: submit, isPending: false };
  },
}));
vi.mock('../../lib/normalizeImage', () => ({
  normalizeImage: (...args: unknown[]) => normalizeImage(...args),
}));

const jpeg = (name = 'm.jpg', size = 10) => new File([new Uint8Array(size)], name, { type: 'image/jpeg' });

function renderDialog(pending?: MeterReading) {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <SubmitReadingDialog
        plotId="p7"
        meter={plotMeter}
        pending={pending}
        onSubmitted={vi.fn()}
        onClose={vi.fn()}
      />
    </QueryClientProvider>,
  );
}

describe('SubmitReadingDialog photo', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    submit.mockReset();
    check.mockReset();
    check.mockResolvedValue({ meterId: 'm1', takenOn: '2026-11-06', warnings: [] });
    normalizeImage.mockReset();
    normalizeImage.mockImplementation((file: File) => Promise.resolve(file));
    let counter = 0;
    URL.createObjectURL = vi.fn(() => `blob:test/${++counter}`);
    URL.revokeObjectURL = vi.fn();
  });

  it('offers the camera and submits the photo with the reading', async () => {
    const user = userEvent.setup();
    renderDialog();
    const input = screen.getByTestId('photo-input');
    expect(input).toHaveAttribute('accept', 'image/*');
    expect(input).toHaveAttribute('capture', 'environment');
    expect(screen.getByRole('button', { name: 'Додати фото лічильника' })).toHaveClass('min-h-11');
    const file = jpeg();
    await user.upload(input, file);
    expect(await screen.findByRole('img', { name: 'Фото лічильника' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Замінити' })).toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Показ, /), '8300.5');
    await user.click(screen.getByRole('button', { name: 'Передати показ' }));
    await waitFor(() =>
      expect(submit).toHaveBeenCalledWith({
        id: 'p7',
        data: { reading: { meterId: 'm1', takenOn: undefined, value: '8300.5' }, photo: file },
      }),
    );
  });

  it('removes the photo before saving and revokes its object URL', async () => {
    const user = userEvent.setup();
    renderDialog();
    await user.upload(screen.getByTestId('photo-input'), jpeg());
    await screen.findByRole('img', { name: 'Фото лічильника' });
    await user.click(screen.getByRole('button', { name: 'Прибрати' }));
    await waitFor(() => expect(screen.queryByRole('img')).not.toBeInTheDocument());
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test/1');
    expect(screen.getByRole('button', { name: 'Додати фото лічильника' })).toBeInTheDocument();
  });

  it('shows the processing state while the photo is prepared', async () => {
    let finish: (file: File) => void = () => undefined;
    normalizeImage.mockReturnValue(new Promise<File>((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    renderDialog();
    await user.upload(screen.getByTestId('photo-input'), jpeg());
    expect(await screen.findByRole('button', { name: 'Обробка…' })).toBeDisabled();
    finish(jpeg());
    expect(await screen.findByRole('button', { name: 'Замінити' })).toBeInTheDocument();
  });

  it('refuses a photo over 5 MB after normalising and one that cannot be used', async () => {
    const user = userEvent.setup();
    renderDialog();
    normalizeImage.mockImplementationOnce((file: File) => Promise.resolve(file));
    await user.upload(screen.getByTestId('photo-input'), jpeg('big.jpg', 5 * 1024 * 1024 + 1));
    expect(await screen.findByText('Фото завелике. Максимум 5 МБ.')).toBeInTheDocument();
    normalizeImage.mockResolvedValueOnce(undefined);
    await user.upload(screen.getByTestId('photo-input'), jpeg('x.jpg'));
    expect(await screen.findByText('Дозволені лише фото (JPEG, PNG).')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('shows a server photo field error and a general notice for an unshown field', async () => {
    renderDialog();
    act(() =>
      mutationOptions.mutation.onError(
        new ApiError(400, {
          code: 'VALIDATION',
          errors: [{ field: 'photo', code: 'FILE_TYPE_NOT_ALLOWED' }],
        }),
      ),
    );
    expect(await screen.findByText('Дозволені лише фото (JPEG, PNG) і PDF.')).toBeInTheDocument();
    act(() =>
      mutationOptions.mutation.onError(
        new ApiError(400, { code: 'FILE_TOO_LARGE', errors: [{ field: 'other', code: 'FILE_TOO_LARGE' }] }),
      ),
    );
    expect(await screen.findByText('Файл завеликий. Максимум 5 МБ.')).toBeInTheDocument();
  });

  it('shows the message of a required photo under the photo field', async () => {
    renderDialog();
    act(() =>
      mutationOptions.mutation.onError(
        new ApiError(400, {
          code: 'VALIDATION',
          errors: [{ field: 'photo', code: 'READING_PHOTO_REQUIRED' }],
        }),
      ),
    );
    expect(
      await screen.findByText('Додайте фото лічильника: правління просить фото до кожного показу.'),
    ).toBeInTheDocument();
  });
});

describe('SubmitReadingDialog check', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    submit.mockReset();
    check.mockReset();
    check.mockResolvedValue({ meterId: 'm1', takenOn: '2026-11-06', warnings: [] });
  });

  async function enter(value: string) {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Показ, /), value);
    await user.click(screen.getByRole('button', { name: 'Передати показ' }));
    return user;
  }

  it('shows the previous reading and the one waiting for approval above the value field', () => {
    renderDialog({
      id: 'r9',
      takenOn: '2026-11-07',
      value: '8300.500',
      status: 'PENDING',
    } as MeterReading);
    expect(screen.getByText('Попередній показ: 8 205 кВт·год на 01.06.2026 (враховано)')).toBeInTheDocument();
    expect(screen.getByText('На підтвердженні: 8 300,5 кВт·год на 07.11.2026')).toBeInTheDocument();
  });

  it('has no pending line when nothing waits', () => {
    renderDialog();
    expect(screen.queryByText(/На підтвердженні/)).not.toBeInTheDocument();
  });

  it('submits straight away when the check has no warning', async () => {
    renderDialog();
    await enter('8300.5');
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
    expect(check).toHaveBeenCalledWith('p7', { meterId: 'm1', takenOn: undefined, value: '8300.5' });
  });

  it('warns about an unusual jump and submits only after «Все одно передати»', async () => {
    check.mockResolvedValue({
      meterId: 'm1',
      takenOn: '2026-11-06',
      warnings: ['UNUSUAL_CONSUMPTION'],
    });
    renderDialog();
    const user = await enter('12331');
    expect(
      await screen.findByText('Перевірте показ: споживання значно більше звичайного'),
    ).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Все одно передати' }));
    expect(submit).toHaveBeenCalledWith({
      id: 'p7',
      data: { reading: { meterId: 'm1', takenOn: undefined, value: '12331' }, photo: undefined },
    });
  });

  it('lets the member correct the value after the warning', async () => {
    check.mockResolvedValue({ meterId: 'm1', takenOn: '2026-11-06', warnings: ['UNUSUAL_CONSUMPTION'] });
    renderDialog();
    const user = await enter('12331');
    await user.click(await screen.findByRole('button', { name: 'Виправити показ' }));
    expect(
      screen.queryByText('Перевірте показ: споживання значно більше звичайного'),
    ).not.toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });

  it('shows a rule break of the check as a field error without submitting', async () => {
    check.mockResolvedValue({
      meterId: 'm1',
      takenOn: '2026-11-06',
      warnings: [],
      problem: { code: 'READING_OUT_OF_ORDER', field: 'value' },
    });
    renderDialog();
    await enter('1');
    expect(
      await screen.findByText('Показ менший за попередній або більший за наступний'),
    ).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });

  it('falls back to a plain submit when the check request fails', async () => {
    check.mockRejectedValue(new Error('offline'));
    renderDialog();
    await enter('8300.5');
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  });
});

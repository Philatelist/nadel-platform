import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@nadel/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { plotMeter } from '../../test/meterFixtures';
import { renderWithApp } from '../../test/render';
import { SubmitReadingDialog } from './SubmitReadingDialog';

const submit = vi.fn();
let mutationOptions: { mutation: { onError: (error: unknown) => void } };
const normalizeImage = vi.fn();

vi.mock('@nadel/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@nadel/api-client')>()),
  useSubmitPlotReadingWithPhoto: (o: typeof mutationOptions) => {
    mutationOptions = o;
    return { mutate: submit, isPending: false };
  },
}));
vi.mock('../../lib/normalizeImage', () => ({
  normalizeImage: (...args: unknown[]) => normalizeImage(...args),
}));

const jpeg = (name = 'm.jpg', size = 10) => new File([new Uint8Array(size)], name, { type: 'image/jpeg' });

function renderDialog() {
  renderWithApp(
    <QueryClientProvider client={new QueryClient()}>
      <SubmitReadingDialog plotId="p7" meter={plotMeter} onSubmitted={vi.fn()} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('SubmitReadingDialog photo', () => {
  beforeEach(() => {
    window.localStorage.setItem('nadel.locale', 'uk');
    submit.mockReset();
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
    expect(submit).toHaveBeenCalledWith({
      id: 'p7',
      data: { reading: { meterId: 'm1', takenOn: undefined, value: '8300.5' }, photo: file },
    });
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

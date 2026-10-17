import { render, type RenderOptions, type RenderResult } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { I18nProvider } from '../i18n';

interface RenderWithAppOptions extends Omit<RenderOptions, 'wrapper'> {
  route?: string;
}

/** Renders a component inside a MemoryRouter and the app's i18n provider (Ukrainian by default). */
export function renderWithApp(
  ui: ReactElement,
  { route = '/', ...options }: RenderWithAppOptions = {},
): RenderResult {
  return render(ui, {
    wrapper: ({ children }) => (
      <MemoryRouter initialEntries={[route]}>
        <I18nProvider>{children}</I18nProvider>
      </MemoryRouter>
    ),
    ...options,
  });
}

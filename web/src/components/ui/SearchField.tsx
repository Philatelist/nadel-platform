import type { ComponentProps } from 'react';
import { TextField } from './TextField';

export function SearchField(
  props: { label: string; error?: string; hint?: string } & Omit<ComponentProps<'input'>, 'id' | 'type'>,
) {
  return <TextField {...props} type="search" />;
}

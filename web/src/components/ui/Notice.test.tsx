import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Notice } from './Notice';

describe('Notice', () => {
  it('uses role alert for danger', () => {
    render(<Notice tone="danger">Failed</Notice>);
    expect(screen.getByRole('alert')).toHaveTextContent('Failed');
  });

  it.each(['warning', 'success'] as const)('uses role status for %s', (tone) => {
    render(<Notice tone={tone}>Note</Notice>);
    expect(screen.getByRole('status')).toHaveTextContent('Note');
  });
});

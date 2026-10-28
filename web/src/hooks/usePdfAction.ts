import { useState } from 'react';
import { downloadBlob } from '../downloadBlob';

/**
 * A button action that fetches a PDF as a Blob (through a generated client function) and saves it as a file.
 * `error` holds the failure of the last run, for `problemMessage`.
 */
export function usePdfAction(): {
  run: (fetchPdf: () => Promise<Blob>, fileName: string) => Promise<void>;
  pending: boolean;
  error: unknown;
} {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const run = async (fetchPdf: () => Promise<Blob>, fileName: string): Promise<void> => {
    setPending(true);
    setError(undefined);
    try {
      downloadBlob(await fetchPdf(), fileName);
    } catch (caught: unknown) {
      setError(caught);
    } finally {
      setPending(false);
    }
  };
  return { run, pending, error };
}
